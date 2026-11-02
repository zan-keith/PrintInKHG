@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.example.khgprint

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private enum class Page { HOME, LOGIN, EDITOR, OPTIONS, RESULT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KHGPrintTheme { PrintApp(this) } }
    }
}

@Composable
private fun PrintApp(activity: Activity) {
    val prefs = remember { activity.getSharedPreferences("khg_ui", Context.MODE_PRIVATE) }
    val store = remember { CredentialStore(activity) }
    val client = remember { IppClient() }
    val scope = rememberCoroutineScope()
    var introStep by rememberSaveable { mutableIntStateOf(if (prefs.getBoolean("onboarded", false)) 3 else 0) }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var rememberLogin by rememberSaveable { mutableStateOf(true) }
    var savedLoaded by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<Uri?>(null) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var printerStatus by rememberSaveable { mutableStateOf("Not checked yet") }
    var activityStatus by rememberSaveable { mutableStateOf("Ready when you are") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageRange by rememberSaveable { mutableStateOf("") }
    var copies by rememberSaveable { mutableIntStateOf(1) }
    var duplex by rememberSaveable { mutableStateOf(false) }
    var colorMode by rememberSaveable { mutableStateOf("Color") }
    var printSucceeded by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pdf = uri
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected PDF"
        page = Page.EDITOR
    }

    if (introStep < 3) {
        Onboarding(step = introStep, onNext = { if (introStep == 2) { prefs.edit().putBoolean("onboarded", true).apply(); introStep = 3 } else introStep++ }, onSkip = { prefs.edit().putBoolean("onboarded", true).apply(); introStep = 3 })
        return
    }

    if (!savedLoaded) {
        store.read()?.let { username = it.first; password = it.second }
        savedLoaded = true
    }

    val openLogin = { page = Page.LOGIN; error = null }
    val openPdf = {
        if (store.hasCredentials()) picker.launch(arrayOf("application/pdf")) else openLogin()
    }
    Scaffold(
        containerColor = KHGBackground,
        topBar = { AppTopBar(page, onBack = { page = Page.HOME }) },
        bottomBar = { if (page == Page.HOME) BottomBar(page, onHome = { page = Page.HOME }, onConnection = openLogin) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { current ->
                when (current) {
                    Page.HOME -> HomeScreen(printerStatus, activityStatus, store.hasCredentials(), openLogin, openPdf, { store.clear(); printerStatus = "Not checked yet"; activityStatus = "Saved login cleared" })
                    Page.LOGIN -> LoginScreen(username, password, rememberLogin, busy, error, { username = it }, { password = it }, { rememberLogin = it }, {
                        if (username.isBlank() || password.isBlank()) { error = "Enter your username and password."; return@LoginScreen }
                        if (rememberLogin) store.save(username.trim(), password)
                        busy = true; error = null
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { client.check(username.trim(), password) }.getOrNull()
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                busy = false
                                if (result?.isSuccess() == true) { printerStatus = "Connected · PDF ready"; activityStatus = result.summary(); page = Page.HOME } else error = "Could not reach the printer. Join KHG-WiFi and try again."
                            }
                        }
                    }, { page = Page.HOME })
                    Page.EDITOR -> EditorScreen(fileName, pageRange, copies, duplex, colorMode, { pageRange = it }, { copies = it }, { duplex = it }, { colorMode = it }, { page = Page.OPTIONS }, { picker.launch(arrayOf("application/pdf")) })
                    Page.OPTIONS -> OptionsScreen(fileName, pageRange.ifBlank { "All pages" }, copies, duplex, colorMode, printerStatus, busy, { page = Page.EDITOR }) {
                        val credentials = store.read()
                        val uri = pdf
                        if (credentials == null || uri == null) { page = Page.LOGIN; return@OptionsScreen }
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { client.printPdf(credentials.first, credentials.second, activity.contentResolver, uri, pageRange.ifBlank { "All pages" }, copies, duplex, colorMode) }.getOrNull()
                            kotlinx.coroutines.withContext(Dispatchers.Main) { busy = false; printSucceeded = result?.isSuccess() == true; activityStatus = if (printSucceeded) "Print job accepted · ${result?.summary()}" else "Print failed — check connection"; page = Page.RESULT }
                        }
                    }
                    Page.RESULT -> ResultScreen(printSucceeded, activityStatus) { page = Page.HOME }
                }
            }
        }
    }
}

@Composable private fun Onboarding(step: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val titles = listOf("Print without the guesswork", "Prepare every PDF", "Your access stays private")
    val bodies = listOf("A focused way to send documents to the KHG printer from your phone.", "Choose pages, copies, colour and duplex before anything reaches the printer.", "Your basic-login details are protected by Android Keystore and never written to logs.")
    Surface(Modifier.fillMaxSize(), color = KHGBackground) { Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("KHG / PRINT", style = MaterialTheme.typography.labelLarge, color = KHGTeal, fontWeight = FontWeight.Bold); Text("${step + 1} / 3", style = MaterialTheme.typography.labelMedium, color = KHGMuted) }
        Spacer(Modifier.height(60.dp)); Box(Modifier.size(112.dp).background(KHGTeal.copy(alpha=.14f), CircleShape), contentAlignment = Alignment.Center) { Icon(if(step==0) Icons.Default.Print else if(step==1) Icons.Default.Description else Icons.Default.Lock, null, tint = KHGTeal, modifier = Modifier.size(52.dp)) }
        Spacer(Modifier.height(34.dp)); Text(titles[step], style = MaterialTheme.typography.headlineLarge, color = KHGInk, fontWeight = FontWeight.Bold); Spacer(Modifier.height(14.dp)); Text(bodies[step], style = MaterialTheme.typography.bodyLarge, color = KHGMuted, lineHeight = 26.sp); Spacer(Modifier.weight(1f)); LinearProgressIndicator({ (step + 1) / 3f }, Modifier.fillMaxWidth(), color = KHGTeal, trackColor = KHGTeal.copy(alpha=.14f)); Spacer(Modifier.height(24.dp)); Button(onClick = onNext, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = KHGTeal)) { Text(if(step==2) "Get started" else "Continue", fontWeight = FontWeight.Bold) }; TextButton(onClick = onSkip) { Text("Skip introduction", color = KHGTeal) }
    } }
}

@Composable private fun AppTopBar(page: Page, onBack: () -> Unit) { if(page != Page.HOME) TopAppBar(title = { Text(when(page){Page.LOGIN->"Connect printer";Page.EDITOR->"Prepare PDF";Page.OPTIONS->"Print options";Page.RESULT->"Job status";else->"KHG Print"}, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = KHGBackground, titleContentColor = KHGInk)) else TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Print,null,tint=KHGTeal,modifier=Modifier.size(26.dp));Spacer(Modifier.width(10.dp));Text("KHG Print",fontWeight=FontWeight.Bold)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=KHGBackground,titleContentColor=KHGInk)) }

@Composable private fun HomeScreen(status:String, activity:String, hasLogin:Boolean, login:()->Unit, pick:()->Unit, clear:()->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) { item { Text("What are you printing today?", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Your documents stay on your device until you send them.", style=MaterialTheme.typography.bodyMedium, color=KHGMuted) }; item { ElevatedCard(shape=RoundedCornerShape(20.dp), colors=CardDefaults.elevatedCardColors(containerColor=Color.White)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Wifi, null, tint=KHGTeal);Spacer(Modifier.width(12.dp));Column{Text("PRINTER CONNECTION",style=MaterialTheme.typography.labelMedium,color=KHGMuted);Text(status,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.SemiBold)}};Spacer(Modifier.height(16.dp));OutlinedButton(onClick=login,Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){Text(if(hasLogin)"Check printer again" else "Connect printer")}} } }; item { Button(onClick=pick,Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Choose a PDF",fontWeight=FontWeight.Bold)} }; item { Text("Tip  ·  Start with a one-page PDF while testing.",style=MaterialTheme.typography.bodySmall,color=KHGMuted,modifier=Modifier.padding(horizontal=4.dp)) }; item { OutlinedCard(shape=RoundedCornerShape(20.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color.Transparent)){Column(Modifier.padding(20.dp)){Text("LATEST ACTIVITY",style=MaterialTheme.typography.labelMedium,color=KHGMuted);Spacer(Modifier.height(8.dp));Text(activity,style=MaterialTheme.typography.bodyLarge,color=KHGInk)}}} ; item { TextButton(onClick=clear){Text("Clear saved login",color=KHGTeal)} } } }

@Composable private fun LoginScreen(user:String, pass:String, remember:Boolean, busy:Boolean, error:String?, onUser:(String)->Unit, onPass:(String)->Unit, onRemember:(Boolean)->Unit, submit:()->Unit, cancel:()->Unit) { var show by rememberSaveable{mutableStateOf(false)}; LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("Connect to P3055",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold);Text("davinci.khg.jku.at  ·  KHG-WiFi",style=MaterialTheme.typography.bodyMedium,color=KHGMuted)};item{OutlinedTextField(user,onUser,Modifier.fillMaxWidth(),label={Text("KHG username")},singleLine=true,leadingIcon={Icon(Icons.Default.Person,null)})};item{OutlinedTextField(pass,onPass,Modifier.fillMaxWidth(),label={Text("Password")},singleLine=true,visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Password),leadingIcon={Icon(Icons.Default.Lock,null)},trailingIcon={IconButton({show=!show}){Icon(if(show)Icons.Default.VisibilityOff else Icons.Default.Visibility,"Show password")}})};item{Row(verticalAlignment=Alignment.CenterVertically){Switch(remember,onRemember);Spacer(Modifier.width(12.dp));Text("Remember this login on this device",color=KHGInk)}};item{Text("Stored using Android Keystore and used only for the configured printer.",style=MaterialTheme.typography.bodySmall,color=KHGMuted)};error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}};item{Button(submit,Modifier.fillMaxWidth().height(54.dp),enabled=!busy,shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Check connection",fontWeight=FontWeight.Bold)}};item{TextButton(cancel,Modifier.fillMaxWidth()){Text("Cancel",color=KHGTeal)}}} }

@Composable private fun EditorScreen(name:String, range:String, count:Int, twoSided:Boolean, colour:String, onRange:(String)->Unit, onCount:(Int)->Unit, onDuplex:(Boolean)->Unit, onColour:(String)->Unit, next:()->Unit, chooseOther:()->Unit) { LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Review before printing",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold)};item{ElevatedCard(shape=RoundedCornerShape(18.dp),colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.PictureAsPdf,null,tint=Color(0xffd64d4d),modifier=Modifier.size(34.dp));Spacer(Modifier.width(14.dp));Column{Text(name,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.SemiBold);Text("PDF · original stays unchanged",style=MaterialTheme.typography.bodySmall,color=KHGMuted)}}}};item{OutlinedTextField(range,onRange,Modifier.fillMaxWidth(),label={Text("Pages")},placeholder={Text("All pages or 1-3, 5")},singleLine=true)};item{Text("Copies  ·  $count",style=MaterialTheme.typography.labelLarge,color=KHGMuted);Slider(count.toFloat(),{onCount(it.toInt().coerceIn(1,20))},valueRange=1f..20f,steps=18,colors=SliderDefaults.colors(thumbColor=KHGTeal,activeTrackColor=KHGTeal))};item{SettingRow("Double-sided", "Turn over on the long edge", twoSided, onDuplex)};item{Text("Colour",style=MaterialTheme.typography.labelLarge,color=KHGMuted);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){listOf("Color","B&W").forEachIndexed{index,label->SegmentedButton(selected=if(colour=="Color")index==0 else index==1,onClick={onColour(if(index==0)"Color" else "B&W")},shape=SegmentedButtonDefaults.itemShape(index,2)){Text(label)}}}};item{Text("This is a print-preparation editor: it never overwrites the source PDF.",style=MaterialTheme.typography.bodySmall,color=KHGMuted)};item{Button(next,Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Text("Review print options",fontWeight=FontWeight.Bold)}};item{TextButton(chooseOther,Modifier.fillMaxWidth()){Text("Choose another PDF",color=KHGTeal)}}} }

@Composable private fun SettingRow(title:String,sub:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().clickable{onChange(!checked)},verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge,color=KHGInk);Text(sub,style=MaterialTheme.typography.bodySmall,color=KHGMuted)};Switch(checked,onChange)}}
@Composable private fun OptionsScreen(name:String,range:String,count:Int,twoSided:Boolean,colour:String,status:String,busy:Boolean,back:()->Unit,send:()->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("One last check",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold);Text("Nothing is sent until you confirm below.",style=MaterialTheme.typography.bodyMedium,color=KHGMuted)};item{ElevatedCard(shape=RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){Column(Modifier.padding(20.dp)){Text(name,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.Bold);HorizontalDivider(Modifier.padding(vertical=14.dp));SummaryRow("Pages",range);SummaryRow("Copies",count.toString());SummaryRow("Finish",if(twoSided)"Double-sided" else "Single-sided");SummaryRow("Colour",colour)}}};item{Card(colors=CardDefaults.cardColors(containerColor=KHGTeal.copy(alpha=.10f)),shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Wifi,null,tint=KHGTeal);Spacer(Modifier.width(12.dp));Column{Text("P3055",fontWeight=FontWeight.Bold,color=KHGInk);Text(status,style=MaterialTheme.typography.bodySmall,color=KHGMuted)}}}};item{Button(send,Modifier.fillMaxWidth().height(56.dp),enabled=!busy,shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else{Icon(Icons.Default.Send,null);Spacer(Modifier.width(8.dp));Text("Send to printer",fontWeight=FontWeight.Bold)}}};item{TextButton(back,Modifier.fillMaxWidth()){Text("Back to editor",color=KHGTeal)}}} }
@Composable private fun SummaryRow(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=KHGMuted);Text(value,color=KHGInk,fontWeight=FontWeight.SemiBold)}}
@Composable private fun ResultScreen(ok:Boolean,detail:String,home:()->Unit){Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(88.dp).background((if(ok)KHGTeal else Color(0xffd98745)).copy(alpha=.14f),CircleShape),contentAlignment=Alignment.Center){Icon(if(ok)Icons.Default.Check else Icons.Default.Warning,null,tint=if(ok)KHGTeal else Color(0xffd98745),modifier=Modifier.size(46.dp))};Spacer(Modifier.height(24.dp));Text(if(ok)"Your job was accepted" else "The printer did not accept the job",style=MaterialTheme.typography.headlineSmall,color=KHGInk,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text(if(ok)"Accepted by CUPS does not guarantee the pages have physically printed." else "Check KHG-WiFi, printer access and saved credentials, then try again.",style=MaterialTheme.typography.bodyLarge,color=KHGMuted);Spacer(Modifier.height(28.dp));Text(detail,style=MaterialTheme.typography.bodySmall,color=KHGMuted);Spacer(Modifier.height(32.dp));Button(home,Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Text("Back to home",fontWeight=FontWeight.Bold)}}}
@Composable private fun BottomBar(page:Page,onHome:()->Unit,onConnection:()->Unit){NavigationBar(containerColor=Color.White){NavigationBarItem(selected=true,onClick=onHome,icon={Icon(Icons.Default.Home,null)},label={Text("Home")});NavigationBarItem(selected=false,onClick=onConnection,icon={Icon(Icons.Default.Settings,null)},label={Text("Connection")})}}

private val KHGBackground=Color(0xfff7f8fa); private val KHGInk=Color(0xff172033); private val KHGTeal=Color(0xff168c83); private val KHGMuted=Color(0xff667085)
private val KHGColors=lightColorScheme(primary=KHGTeal,onPrimary=Color.White,secondary=Color(0xffe59b55),background=KHGBackground,surface=Color.White,onSurface=KHGInk)
@Composable private fun KHGPrintTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = KHGColors, typography = Typography(), content = content) }

private class CredentialStore(private val context:Context){private val prefs=context.getSharedPreferences("khg_secure_credentials",Context.MODE_PRIVATE);private val alias="khg_print_login";private fun key():javax.crypto.SecretKey{val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)};if(ks.containsAlias(alias))return(ks.getEntry(alias,null)as java.security.KeyStore.SecretKeyEntry).secretKey;val g=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,3).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey()};fun save(user:String,pass:String){val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.ENCRYPT_MODE,key());prefs.edit().putString("user",user).putString("data",android.util.Base64.encodeToString(c.iv+c.doFinal(pass.toByteArray()),android.util.Base64.NO_WRAP)).apply()};fun read():Pair<String,String>?=try{val u=prefs.getString("user",null)?:return null;val raw=android.util.Base64.decode(prefs.getString("data","") ,android.util.Base64.NO_WRAP);val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.DECRYPT_MODE,key(),javax.crypto.spec.GCMParameterSpec(128,raw.copyOfRange(0,12)));u to String(c.doFinal(raw.copyOfRange(12,raw.size)))}catch(_:Exception){null};fun hasCredentials()=read()!=null;fun clear(){prefs.edit().clear().apply();try{java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}catch(_:Exception){}}}
