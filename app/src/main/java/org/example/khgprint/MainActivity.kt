@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.example.khgprint

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private enum class Page { HOME, CONNECT, TUTORIAL, SETTINGS, ABOUT, PRIVACY, LOGIN, EDITOR, OPTIONS, RESULT }

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
    var duplex by rememberSaveable { mutableStateOf(true) }
    var colorMode by rememberSaveable { mutableStateOf("B&W") }
    var printSucceeded by rememberSaveable { mutableStateOf(false) }
    var confirmEditorExit by rememberSaveable { mutableStateOf(false) }
    var loginReturnPage by rememberSaveable { mutableStateOf(Page.HOME) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pdf = uri
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected PDF"
        pageRange = ""
        copies = 1
        duplex = true
        colorMode = "B&W"
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

    BackHandler(enabled = page != Page.HOME) {
        if (page == Page.EDITOR) {
            confirmEditorExit = true
        } else {
            page = when (page) {
                Page.CONNECT -> Page.HOME
                Page.TUTORIAL -> Page.CONNECT
                Page.SETTINGS -> Page.HOME
                Page.ABOUT -> Page.SETTINGS
                Page.PRIVACY -> Page.ABOUT
                Page.LOGIN -> loginReturnPage
                Page.OPTIONS -> Page.EDITOR
                Page.RESULT -> Page.HOME
                else -> Page.HOME
            }
        }
    }

    if (confirmEditorExit) {
        AlertDialog(
            onDismissRequest = { confirmEditorExit = false },
            icon = { Icon(Icons.Default.WarningAmber, null, tint = Color(0xffd98745)) },
            title = { Text("Leave PDF editor?") },
            text = { Text("Your current page range, copies, and print settings will be discarded. The original PDF will not be changed.") },
            confirmButton = { TextButton(onClick = { confirmEditorExit = false; page = Page.HOME }) { Text("Leave editor", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmEditorExit = false }) { Text("Stay") } }
        )
    }

    val openLogin = { loginReturnPage = page; page = Page.LOGIN; error = null }
    val openPdf = {
        if (store.hasCredentials()) picker.launch(arrayOf("application/pdf")) else openLogin()
    }
    Scaffold(
        containerColor = KHGBackground,
        topBar = { AppTopBar(page, onBack = { page = when(page) { Page.TUTORIAL -> Page.CONNECT; Page.ABOUT -> Page.SETTINGS; Page.LOGIN -> Page.CONNECT; Page.EDITOR -> Page.HOME; Page.OPTIONS -> Page.EDITOR; Page.RESULT -> Page.HOME; else -> Page.HOME } }) },
        bottomBar = { if (page in setOf(Page.HOME, Page.CONNECT, Page.TUTORIAL, Page.SETTINGS, Page.ABOUT)) BottomBar(page, onHome = { page = Page.HOME }, onConnection = { page = Page.CONNECT }, onSettings = { page = Page.SETTINGS }) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { current ->
                when (current) {
                    Page.HOME -> HomeScreen(printerStatus, activityStatus, store.hasCredentials(), openLogin, openPdf, { store.clear(); printerStatus = "Not checked yet"; activityStatus = "Saved login cleared" })
                    Page.CONNECT -> ConnectScreen(openLogin, { page = Page.TUTORIAL }, { page = Page.ABOUT }, { openExternal(activity, it) })
                    Page.TUTORIAL -> TutorialScreen({ page = Page.CONNECT }, openPdf)
                    Page.SETTINGS -> SettingsScreen(store.hasCredentials(), { page = Page.LOGIN }, { store.clear(); printerStatus = "Not checked yet"; activityStatus = "Logged out" }, { page = Page.ABOUT })
                    Page.ABOUT -> AboutScreen({ page = Page.SETTINGS }, { page = Page.PRIVACY }, { store.clear(); printerStatus = "Not checked yet"; activityStatus = "Logged out" }, { openExternal(activity, it) })
                    Page.PRIVACY -> PrivacyScreen { page = Page.ABOUT }
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
                            val attempt = runCatching { client.printPdf(credentials.first, credentials.second, activity.contentResolver, uri, pageRange.ifBlank { "All pages" }, copies, duplex, colorMode) }
                            val result = attempt.getOrNull()
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                busy = false
                                printSucceeded = result?.isSuccess() == true
                                activityStatus = when {
                                    printSucceeded -> "Print job accepted · ${result?.summary()}"
                                    result != null -> "Print rejected · ${result.summary()}"
                                    else -> "Print failed · ${attempt.exceptionOrNull()?.message ?: "Could not reach the printer"}"
                                }
                                page = Page.RESULT
                            }
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

@Composable private fun AppTopBar(page: Page, onBack: () -> Unit) { if(page != Page.HOME) TopAppBar(title = { Text(when(page){Page.CONNECT->"Connect & setup";Page.TUTORIAL->"How to print";Page.SETTINGS->"Settings";Page.ABOUT->"About & developer";Page.PRIVACY->"Privacy & data";Page.LOGIN->"Connect printer";Page.EDITOR->"Prepare PDF";Page.OPTIONS->"Print options";Page.RESULT->"Job status";else->"PrintInKHG"}, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = KHGBackground, titleContentColor = KHGInk)) else TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.ic_khg_logo),"PrintInKHG logo",modifier=Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)));Spacer(Modifier.width(10.dp));Text("PrintInKHG",fontWeight=FontWeight.Bold)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=KHGBackground,titleContentColor=KHGInk)) }

@Composable private fun HomeScreen(status:String, activity:String, hasLogin:Boolean, login:()->Unit, pick:()->Unit, clear:()->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) { item { Text("What are you printing today?", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Your documents stay on your device until you send them.", style=MaterialTheme.typography.bodyMedium, color=KHGMuted) }; item { ElevatedCard(shape=RoundedCornerShape(20.dp), colors=CardDefaults.elevatedCardColors(containerColor=Color.White)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Wifi, null, tint=KHGTeal);Spacer(Modifier.width(12.dp));Column{Text("PRINTER CONNECTION",style=MaterialTheme.typography.labelMedium,color=KHGMuted);Text(status,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.SemiBold)}};Spacer(Modifier.height(16.dp));OutlinedButton(onClick=login,Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){Text(if(hasLogin)"Check printer again" else "Connect printer")}} } }; item { Button(onClick=pick,Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Choose a PDF",fontWeight=FontWeight.Bold)} }; item { Text("Tip  ·  Start with a one-page PDF while testing.",style=MaterialTheme.typography.bodySmall,color=KHGMuted,modifier=Modifier.padding(horizontal=4.dp)) }; item { OutlinedCard(shape=RoundedCornerShape(20.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color.Transparent)){Column(Modifier.padding(20.dp)){Text("LATEST ACTIVITY",style=MaterialTheme.typography.labelMedium,color=KHGMuted);Spacer(Modifier.height(8.dp));Text(activity,style=MaterialTheme.typography.bodyLarge,color=KHGInk)}}} ; item { TextButton(onClick=clear){Text("Clear saved login",color=KHGTeal)} } } }

@Composable private fun ConnectScreen(openLogin:()->Unit, openTutorial:()->Unit, openAbout:()->Unit, openUrl:(String)->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Set up KHG printing", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Follow these steps once. After that, printing is just choose → review → send.", style=MaterialTheme.typography.bodyLarge, color=KHGMuted) }
    item { OutlinedButton(openTutorial, Modifier.fillMaxWidth(), shape=RoundedCornerShape(14.dp)) { Icon(Icons.Default.PlayCircleOutline, null); Spacer(Modifier.width(8.dp)); Text("Open the quick tutorial") } }
    item { GuideStep("1", "Join the right Wi‑Fi", "Connect your phone to KHG-WiFi. The printer server is normally reachable only from the KHG network or an approved connection.") }
    item { GuideStep("2", "Open the resident homepage", "Use the official KHG resident portal for current information and network instructions.", "davinci.khg.jku.at") { openUrl("https://davinci.khg.jku.at") } }
    item { GuideStep("3", "Check the printer address", "This app currently targets P3055 through IPP over HTTPS. The server URL or queue can change, so verify the latest address with KHG before troubleshooting.", "https://davinci.khg.jku.at:631/printers/P3055") { openUrl("https://davinci.khg.jku.at:631/printers/P3055") } }
    item { GuideStep("4", "Enter your KHG login", "Tap below and use your normal basic-login credentials. If you choose remember, the app encrypts them with Android Keystore.") }
    item { Button(openLogin, Modifier.fillMaxWidth().height(54.dp), shape=RoundedCornerShape(14.dp), colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text("Connect printer", fontWeight=FontWeight.Bold) } }
    item { Text("Need help?", style=MaterialTheme.typography.titleMedium, color=KHGInk, fontWeight=FontWeight.Bold) }
    item { GuideStep("•", "KHG-Dormlife", "Resident community information and updates.", "khg-dormlife.club") { openUrl("https://khg-dormlife.club/") } }
    item { GuideStep("•", "Internal mailing list", "You are automatically assigned to tratsch@khg.jku.at.", "tratsch@khg.jku.at") { openUrl("mailto:tratsch@khg.jku.at") } }
    item { GuideStep("•", "Questions or problems", "Contact admin@khg.jku.at or write directly to one of the admins.", "admin@khg.jku.at") { openUrl("mailto:admin@khg.jku.at") } }
    item { GuideStep("•", "App developer help", "For issues with this unofficial app, contact zan-keith directly.", "zankeith.contact@gmail.com") { openUrl("mailto:zankeith.contact@gmail.com") } }
    item { Text("Because this app is unofficial, contacting KHG administration about an app problem may not help. Please raise an issue on GitHub or email the developer directly.", style=MaterialTheme.typography.bodySmall, color=KHGMuted, lineHeight=18.sp, modifier=Modifier.padding(horizontal=4.dp)) }
    item { TextButton(openAbout, Modifier.fillMaxWidth()) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("About, developer details & debug options", color=KHGTeal) } }
    item { BrandFooter() }
} }

@Composable private fun GuideStep(number:String, title:String, body:String, link:String?=null, open:()->Unit={}) { Card(Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) { Row(Modifier.padding(16.dp), verticalAlignment=Alignment.Top) { Box(Modifier.size(30.dp).background(KHGTeal.copy(alpha=.12f), CircleShape), contentAlignment=Alignment.Center) { Text(number, color=KHGTeal, fontWeight=FontWeight.Bold) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.titleSmall, color=KHGInk, fontWeight=FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(body, style=MaterialTheme.typography.bodyMedium, color=KHGMuted); if(link != null) { Spacer(Modifier.height(8.dp)); TextButton(onClick=open, contentPadding=PaddingValues(0.dp)) { Icon(Icons.Default.OpenInNew, null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(link, color=KHGTeal) } } } } } }

@Composable private fun AboutScreen(back:()->Unit, openPrivacy:()->Unit, clearLogin:()->Unit, openUrl:(String)->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("PrintInKHG", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Unofficial resident printing app", style=MaterialTheme.typography.titleMedium, color=KHGTeal); Text("Made with ♥ by zan-keith", style=MaterialTheme.typography.bodyLarge, color=KHGMuted) }
    item { InfoCard("How the app works", "1. It checks the configured KHG CUPS/IPP queue over verified HTTPS.\n2. You choose a PDF using Android's system file picker.\n3. The app prepares page range, copies, colour and duplex options.\n4. After confirmation, it sends an authenticated IPP Print-Job.\n\nNo Linux printer driver or CUPS server is installed on your phone.") }
    item { InfoCard("Privacy and credentials", "Credentials are kept in memory for requests. If you enable Remember this login, they are encrypted with Android Keystore on this device. The app does not upload them to zan-keith or store them in a URL or log.") }
    item { InfoCard("Important limitations", "This is an unofficial app and is not operated or supported by KHG. Printer URLs, queue names, login requirements, certificates, and network rules may change. If printing stops working, check the official resident information or contact an admin before assuming the app is broken.") }
    item { Text("Official links", style=MaterialTheme.typography.titleMedium, color=KHGInk, fontWeight=FontWeight.Bold) }
    item { LinkRow("Resident homepage", "https://davinci.khg.jku.at") { openUrl("https://davinci.khg.jku.at") } }
    item { LinkRow("KHG-Dormlife", "https://khg-dormlife.club/") { openUrl("https://khg-dormlife.club/") } }
    item { LinkRow("Developer", "github.com/zan-keith") { openUrl("https://github.com/zan-keith") } }
    item { LinkRow("Problems", "admin@khg.jku.at") { openUrl("mailto:admin@khg.jku.at") } }
    item { OutlinedButton(openPrivacy, Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) { Icon(Icons.Default.Security, null); Spacer(Modifier.width(8.dp)); Text("EU privacy & data notes") } }
    item { Text("Developer / debug options", style=MaterialTheme.typography.titleMedium, color=KHGInk, fontWeight=FontWeight.Bold) }
    item { InfoCard("Debug build", "This build remains debuggable. It uses the fixed endpoint shown below and keeps detailed connection status in the app UI, but never displays or logs passwords.\n\nConfigured queue:\nhttps://davinci.khg.jku.at:631/printers/P3055") }
    item { OutlinedButton(clearLogin, Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Clear saved credentials") } }
    item { TextButton(back, Modifier.fillMaxWidth()) { Text("Back to setup", color=KHGTeal) } }
    item { BrandFooter() }
} }

@Composable private fun PrivacyScreen(back:()->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Privacy & data", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Plain-language notes for residents in the EU/EEA.", style=MaterialTheme.typography.bodyLarge, color=KHGMuted) }
    item { InfoCard("What this app processes", "When you connect or print, the app sends your KHG username, an HTTP Basic authentication request, printer attributes, and—when printing—the selected PDF and chosen print options to the configured KHG IPP/CUPS server over HTTPS.") }
    item { InfoCard("What stays on your phone", "The app has no analytics, advertising, account backend, or developer cloud. The selected PDF is read through Android's system file picker and is not copied into an app upload service. If you enable Remember this login, the username and password are encrypted with Android Keystore on this device.") }
    item { InfoCard("Server-side data", "The KHG printer server may process authentication records, print-job metadata, documents, and operational logs under its own policies. This unofficial app cannot control or promise the retention period of data handled by that server. Check current KHG guidance if you need a formal data-protection answer.") }
    item { InfoCard("Your responsibility", "Only print documents you are authorized to print. Avoid sending sensitive personal data unless you understand the KHG printer's handling and collection rules. Use KHG-WiFi or another explicitly authorized network and never disable certificate validation.") }
    item { InfoCard("EU / GDPR context", "These notes are informational and are not legal advice or a privacy notice for KHG. For questions about the printer service, its legal basis, retention, or data-subject requests, contact the responsible KHG administrator. For app-specific bugs, contact the developer at zankeith.contact@gmail.com or open an issue at github.com/zan-keith.") }
    item { TextButton(back, Modifier.fillMaxWidth()) { Text("Back to About", color=KHGTeal) } }
    item { BrandFooter() }
} }

@Composable private fun InfoCard(title:String, body:String) { Card(shape=RoundedCornerShape(16.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) { Column(Modifier.padding(18.dp)) { Text(title, style=MaterialTheme.typography.titleSmall, color=KHGInk, fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(body, style=MaterialTheme.typography.bodyMedium, color=KHGMuted, lineHeight=22.sp) } } }
@Composable private fun LinkRow(title:String, url:String, open:()->Unit) { OutlinedCard(Modifier.fillMaxWidth().clickable(onClick=open), shape=RoundedCornerShape(14.dp)) { Row(Modifier.padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color=KHGInk, fontWeight=FontWeight.SemiBold); Text(url, color=KHGTeal, style=MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.OpenInNew, null, tint=KHGTeal) } } }

@Composable private fun TutorialScreen(back:()->Unit, choosePdf:()->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Print in four steps", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("A short guide for your first print job.", style=MaterialTheme.typography.bodyLarge, color=KHGMuted) }
    item { GuideStep("1", "Connect to KHG-WiFi", "Your phone needs access to the KHG network before the printer can be checked.") }
    item { GuideStep("2", "Connect your account", "Open Connect, enter your KHG username and password, then tap Check connection. Remembered credentials are encrypted on this device.") }
    item { GuideStep("3", "Choose and prepare a PDF", "Pick a PDF from your phone. Select pages, copies, colour, and single- or double-sided printing. The original file is never changed.") }
    item { GuideStep("4", "Review, then send", "Read the final summary and tap Send to printer only when it looks right. Start with one page while testing.") }
    item { Button(choosePdf, Modifier.fillMaxWidth().height(54.dp), shape=RoundedCornerShape(14.dp), colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Choose a PDF", fontWeight=FontWeight.Bold) } }
    item { Text("If it does not work", style=MaterialTheme.typography.titleMedium, color=KHGInk, fontWeight=FontWeight.Bold) }
    item { InfoCard("Check the basics", "Make sure you are on KHG-WiFi, the printer URL has not changed, and your KHG account works. For help, contact admin@khg.jku.at or one of the admins directly.") }
    item { BrandFooter() }
} }

@Composable private fun SettingsScreen(hasLogin:Boolean, connect:()->Unit, logout:()->Unit, about:()->Unit) { LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Settings", style=MaterialTheme.typography.headlineMedium, color=KHGInk, fontWeight=FontWeight.Bold); Text("Manage access and app information.", style=MaterialTheme.typography.bodyLarge, color=KHGMuted) }
    item { InfoCard("Saved login", if(hasLogin) "A username and encrypted password are saved on this device." else "No login is saved. You will be asked to connect before choosing a PDF.") }
    item { OutlinedButton(connect, Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(if(hasLogin) "Update or check login" else "Connect printer") } }
    item { Button(logout, Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp), colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text(if(hasLogin) "Log out and clear saved login" else "Clear saved login") } }
    item { OutlinedButton(about, Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp)) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("About and developer details") } }
    item { BrandFooter() }
} }

@Composable private fun BrandFooter() { Column(Modifier.fillMaxWidth().padding(top=12.dp, bottom=6.dp), horizontalAlignment=Alignment.CenterHorizontally) { Text("Made with ♥ by zan-keith", style=MaterialTheme.typography.labelMedium, color=KHGMuted); Text("Unofficial KHG Print app", style=MaterialTheme.typography.labelSmall, color=KHGMuted.copy(alpha=.72f)) } }

@Composable private fun LoginScreen(user:String, pass:String, remember:Boolean, busy:Boolean, error:String?, onUser:(String)->Unit, onPass:(String)->Unit, onRemember:(Boolean)->Unit, submit:()->Unit, cancel:()->Unit) { var show by rememberSaveable{mutableStateOf(false)}; LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("Connect to P3055",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold);Text("davinci.khg.jku.at  ·  KHG-WiFi",style=MaterialTheme.typography.bodyMedium,color=KHGMuted)};item{OutlinedTextField(user,onUser,Modifier.fillMaxWidth(),label={Text("KHG username")},singleLine=true,leadingIcon={Icon(Icons.Default.Person,null)})};item{OutlinedTextField(pass,onPass,Modifier.fillMaxWidth(),label={Text("Password")},singleLine=true,visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Password),leadingIcon={Icon(Icons.Default.Lock,null)},trailingIcon={IconButton({show=!show}){Icon(if(show)Icons.Default.VisibilityOff else Icons.Default.Visibility,"Show password")}})};item{Row(verticalAlignment=Alignment.CenterVertically){Switch(remember,onRemember);Spacer(Modifier.width(12.dp));Text("Remember this login on this device",color=KHGInk)}};item{Text("Stored using Android Keystore and used only for the configured printer.",style=MaterialTheme.typography.bodySmall,color=KHGMuted)};error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}};item{Button(submit,Modifier.fillMaxWidth().height(54.dp),enabled=!busy,shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else Text("Check connection",fontWeight=FontWeight.Bold)}};item{TextButton(cancel,Modifier.fillMaxWidth()){Text("Cancel",color=KHGTeal)}}} }

@Composable private fun EditorScreen(name:String, range:String, count:Int, twoSided:Boolean, colour:String, onRange:(String)->Unit, onCount:(Int)->Unit, onDuplex:(Boolean)->Unit, onColour:(String)->Unit, next:()->Unit, chooseOther:()->Unit) { LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("Review before printing",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold)};item{ElevatedCard(shape=RoundedCornerShape(18.dp),colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.PictureAsPdf,null,tint=Color(0xffd64d4d),modifier=Modifier.size(34.dp));Spacer(Modifier.width(14.dp));Column{Text(name,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.SemiBold);Text("PDF · original stays unchanged",style=MaterialTheme.typography.bodySmall,color=KHGMuted)}}}};item{OutlinedTextField(range,onRange,Modifier.fillMaxWidth(),label={Text("Pages")},placeholder={Text("All pages or 1-3, 5")},singleLine=true)};item{Text("Copies  ·  $count",style=MaterialTheme.typography.labelLarge,color=KHGMuted);Slider(count.toFloat(),{onCount(it.toInt().coerceIn(1,20))},valueRange=1f..20f,steps=18,colors=SliderDefaults.colors(thumbColor=KHGTeal,activeTrackColor=KHGTeal))};item{SettingRow("Double-sided", "Turn over on the long edge", twoSided, onDuplex)};item{Text("Colour",style=MaterialTheme.typography.labelLarge,color=KHGMuted);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){listOf("Color","B&W").forEachIndexed{index,label->SegmentedButton(selected=if(colour=="Color")index==0 else index==1,onClick={onColour(if(index==0)"Color" else "B&W")},shape=SegmentedButtonDefaults.itemShape(index,2)){Text(label)}}}};item{Text("This is a print-preparation editor: it never overwrites the source PDF.",style=MaterialTheme.typography.bodySmall,color=KHGMuted)};item{Button(next,Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Text("Review print options",fontWeight=FontWeight.Bold)}};item{TextButton(chooseOther,Modifier.fillMaxWidth()){Text("Choose another PDF",color=KHGTeal)}}} }

@Composable private fun SettingRow(title:String,sub:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().clickable{onChange(!checked)},verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge,color=KHGInk);Text(sub,style=MaterialTheme.typography.bodySmall,color=KHGMuted)};Switch(checked,onChange)}}
@Composable private fun OptionsScreen(name:String,range:String,count:Int,twoSided:Boolean,colour:String,status:String,busy:Boolean,back:()->Unit,send:()->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Text("One last check",style=MaterialTheme.typography.headlineMedium,color=KHGInk,fontWeight=FontWeight.Bold);Text("Nothing is sent until you confirm below.",style=MaterialTheme.typography.bodyMedium,color=KHGMuted)};item{ElevatedCard(shape=RoundedCornerShape(20.dp),colors=CardDefaults.elevatedCardColors(containerColor=Color.White)){Column(Modifier.padding(20.dp)){Text(name,style=MaterialTheme.typography.titleMedium,color=KHGInk,fontWeight=FontWeight.Bold);HorizontalDivider(Modifier.padding(vertical=14.dp));SummaryRow("Pages",range);SummaryRow("Copies",count.toString());SummaryRow("Finish",if(twoSided)"Double-sided" else "Single-sided");SummaryRow("Colour",colour)}}};item{Card(colors=CardDefaults.cardColors(containerColor=KHGTeal.copy(alpha=.10f)),shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Wifi,null,tint=KHGTeal);Spacer(Modifier.width(12.dp));Column{Text("P3055",fontWeight=FontWeight.Bold,color=KHGInk);Text(status,style=MaterialTheme.typography.bodySmall,color=KHGMuted)}}}};item{Button(send,Modifier.fillMaxWidth().height(56.dp),enabled=!busy,shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),color=Color.White,strokeWidth=2.dp)else{Icon(Icons.Default.Send,null);Spacer(Modifier.width(8.dp));Text("Send to printer",fontWeight=FontWeight.Bold)}}};item{TextButton(back,Modifier.fillMaxWidth()){Text("Back to editor",color=KHGTeal)}}} }
@Composable private fun SummaryRow(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=KHGMuted);Text(value,color=KHGInk,fontWeight=FontWeight.SemiBold)}}
@Composable private fun ResultScreen(ok:Boolean,detail:String,home:()->Unit){Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(88.dp).background((if(ok)KHGTeal else Color(0xffd98745)).copy(alpha=.14f),CircleShape),contentAlignment=Alignment.Center){Icon(if(ok)Icons.Default.Check else Icons.Default.Warning,null,tint=if(ok)KHGTeal else Color(0xffd98745),modifier=Modifier.size(46.dp))};Spacer(Modifier.height(24.dp));Text(if(ok)"Your job was accepted" else "The printer did not accept the job",style=MaterialTheme.typography.headlineSmall,color=KHGInk,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text(if(ok)"Accepted by CUPS does not guarantee the pages have physically printed." else "Check KHG-WiFi, printer access and saved credentials, then try again.",style=MaterialTheme.typography.bodyLarge,color=KHGMuted);Spacer(Modifier.height(28.dp));Text(detail,style=MaterialTheme.typography.bodySmall,color=KHGMuted);Spacer(Modifier.height(32.dp));Button(home,Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=KHGTeal)){Text("Back to home",fontWeight=FontWeight.Bold)}}}
@Composable private fun BottomBar(page:Page,onHome:()->Unit,onConnection:()->Unit,onSettings:()->Unit){NavigationBar(containerColor=Color.White){NavigationBarItem(selected=page==Page.HOME,onClick=onHome,icon={Icon(Icons.Default.Home,null)},label={Text("Home")});NavigationBarItem(selected=page in setOf(Page.CONNECT,Page.TUTORIAL),onClick=onConnection,icon={Icon(Icons.Default.Wifi,null)},label={Text("Connect")});NavigationBarItem(selected=page in setOf(Page.SETTINGS,Page.ABOUT),onClick=onSettings,icon={Icon(Icons.Default.Settings,null)},label={Text("Settings")})}}

private fun openExternal(activity: Activity, value: String) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
}

private val KHGBackground=Color(0xfff7f8fa); private val KHGInk=Color(0xff172033); private val KHGTeal=Color(0xff168c83); private val KHGMuted=Color(0xff667085)
private val KHGColors=lightColorScheme(primary=KHGTeal,onPrimary=Color.White,secondary=Color(0xffe59b55),background=KHGBackground,surface=Color.White,onSurface=KHGInk)
@Composable private fun KHGPrintTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = KHGColors, typography = Typography(), content = content) }

private class CredentialStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("khg_secure_credentials", Context.MODE_PRIVATE)
    private val alias = "khg_print_login"

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return (store.getEntry(alias, null) as java.security.KeyStore.SecretKeyEntry).secretKey
        val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val purposes = android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias, purposes).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun save(user: String, pass: String) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val payload = "$user\u0000$pass".toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(payload)
        payload.fill(0)
        prefs.edit().remove("user").putString("payload", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)).apply()
    }

    fun read(): Pair<String, String>? {
        return try {
        val encoded = prefs.getString("payload", null)
        if (encoded == null) {
            val legacyUser = prefs.getString("user", null)
            val legacyData = prefs.getString("data", null)
            if (legacyUser == null || legacyData == null) return null
            val legacyRaw = android.util.Base64.decode(legacyData, android.util.Base64.NO_WRAP)
            val legacyCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            legacyCipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, legacyRaw.copyOfRange(0, 12)))
            val legacyPass = String(legacyCipher.doFinal(legacyRaw.copyOfRange(12, legacyRaw.size)), Charsets.UTF_8)
            save(legacyUser, legacyPass)
            return legacyUser to legacyPass
        }
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        val decoded = String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        val separator = decoded.indexOf('\u0000')
        require(separator > 0)
        decoded.substring(0, separator) to decoded.substring(separator + 1)
        } catch (_: Exception) { null }
    }

    fun hasCredentials() = read() != null

    fun clear() {
        prefs.edit().clear().apply()
        try { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } catch (_: Exception) { }
    }
}
