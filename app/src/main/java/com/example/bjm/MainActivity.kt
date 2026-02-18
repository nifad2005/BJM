package com.example.bjm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bjm.ui.theme.BJMTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

enum class Screen { Home, Scanner, Chat, Profile, Update }

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private lateinit var db: AppDatabase
    private lateinit var updateManager: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        mqttManager = MqttManager.getInstance(this, db.chatDao())
        mqttManager.connect()
        updateManager = UpdateManager(this)

        val serviceIntent = Intent(this, ChatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        enableEdgeToEdge()
        setContent {
            BJMTheme {
                var currentScreen by remember { mutableStateOf(Screen.Home) }
                var selectedFriend by remember { mutableStateOf<Friend?>(null) }
                val scope = rememberCoroutineScope()
                val dao = remember { db.chatDao() }

                var updateInfo by remember { mutableStateOf<AppUpdate?>(null) }
                var isCheckingUpdate by remember { mutableStateOf(false) }
                val currentVersion = remember { updateManager.getCurrentVersion() }

                LaunchedEffect(Unit) {
                    isCheckingUpdate = true
                    updateInfo = updateManager.checkForUpdate()
                    isCheckingUpdate = false
                }

                LaunchedEffect(currentScreen, selectedFriend) {
                    if (currentScreen == Screen.Chat && selectedFriend != null) {
                        mqttManager.currentActiveChatFriendId = selectedFriend?.id
                    } else {
                        mqttManager.currentActiveChatFriendId = null
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) Log.w("MainActivity", "Notification permission denied")
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                BackHandler(enabled = currentScreen != Screen.Home) {
                    currentScreen = Screen.Home
                }

                Surface(color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(400))).togetherWith(
                                    slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(400))
                                )
                            } else {
                                (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(400))).togetherWith(
                                    slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(400))
                                )
                            }
                        },
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(
                                myId = mqttManager.getMyId(),
                                myName = mqttManager.getMyName(),
                                myProfilePic = mqttManager.getMyProfilePic(),
                                friendsFlow = dao.getAllFriends(),
                                onScanClick = { currentScreen = Screen.Scanner },
                                onProfileClick = { currentScreen = Screen.Profile },
                                onFriendClick = { friend ->
                                    selectedFriend = friend
                                    currentScreen = Screen.Chat
                                },
                                onUpdateClick = { currentScreen = Screen.Update },
                                isUpdateAvailable = updateInfo != null
                            )
                            Screen.Scanner -> ScannerScreen(
                                myId = mqttManager.getMyId(),
                                onIdScanned = { id ->
                                    scope.launch {
                                        dao.insertFriend(Friend(id, id))
                                        mqttManager.notifyFriendAdded(id)
                                        currentScreen = Screen.Home
                                    }
                                },
                                onBack = { currentScreen = Screen.Home }
                            )
                            Screen.Chat -> selectedFriend?.let { friend ->
                                val friends by dao.getAllFriends().collectAsState(initial = emptyList())
                                val activeFriend = friends.find { it.id == friend.id } ?: friend

                                ChatScreen(
                                    friend = activeFriend,
                                    messagesFlow = dao.getMessagesWithFriend(friend.id, mqttManager.getMyId()),
                                    onSend = { content -> mqttManager.sendMessage(friend.id, content) },
                                    onTyping = { isTyping -> mqttManager.sendTypingSignal(friend.id, isTyping) },
                                    onSeen = { mqttManager.markAsSeen(friend.id) },
                                    onBack = { currentScreen = Screen.Home }
                                )
                            }
                            Screen.Profile -> ProfileScreen(
                                currentName = mqttManager.getMyName(),
                                currentPicBase64 = mqttManager.getMyProfilePic(),
                                onSave = { name, pic ->
                                    mqttManager.updateMyProfile(name, pic)
                                    currentScreen = Screen.Home
                                },
                                onBack = { currentScreen = Screen.Home }
                            )
                            Screen.Update -> UpdateScreen(
                                update = updateInfo,
                                currentVersion = currentVersion,
                                isRefreshing = isCheckingUpdate,
                                onRefresh = {
                                    scope.launch {
                                        isCheckingUpdate = true
                                        updateInfo = updateManager.checkForUpdate()
                                        delay(500)
                                        isCheckingUpdate = false
                                    }
                                },
                                onBack = { currentScreen = Screen.Home }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mqttManager.isAppInForeground = true
    }

    override fun onStop() {
        super.onStop()
        mqttManager.isAppInForeground = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myId: String,
    myName: String,
    myProfilePic: String?,
    friendsFlow: kotlinx.coroutines.flow.Flow<List<Friend>>,
    onScanClick: () -> Unit,
    onProfileClick: () -> Unit,
    onFriendClick: (Friend) -> Unit,
    onUpdateClick: () -> Unit,
    isUpdateAvailable: Boolean
) {
    val friends by friendsFlow.collectAsState(initial = emptyList())
    var showShareDialog by remember { mutableStateOf(false) }

    if (showShareDialog) {
        ShareAppDialog(link = "https://bot-holdings-bangladesh.vercel.app/apps/bjm", onDismiss = { showShareDialog = false })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("BJM CHAT", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold)) },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    BadgedBox(badge = {
                        if (isUpdateAvailable) {
                            Badge { Text("1") }
                        }
                    }) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Updates") },
                            onClick = { onUpdateClick(); showMenu = false },
                            leadingIcon = { 
                                BadgedBox(badge = { if (isUpdateAvailable) Badge() }) {
                                    Icon(Icons.Default.SystemUpdate, null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share App") },
                            onClick = { showShareDialog = true; showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Profile Settings") },
                            onClick = { onProfileClick(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Settings, null) }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).clickable { onProfileClick() },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = remember(myProfilePic) { ProfileUtils.decodeImage(myProfilePic) }
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), null, modifier = Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))), Alignment.Center) {
                            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(myName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("ID: ${myId.take(12)}...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text("FRIENDS", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(friends, key = { it.id }) { friend ->
                    FriendItem(friend = friend, onClick = { onFriendClick(friend) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    update: AppUpdate?,
    currentVersion: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isNewAvailable = update != null
    val updateUrl = "https://bot-holdings-bangladesh.vercel.app/apps/bjm/update?current=$currentVersion"

    val infiniteTransition = rememberInfiniteTransition(label = "Rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationAngle"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = if (isRefreshing) Modifier.rotate(rotation) else Modifier
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(120.dp).background(
                    if (isNewAvailable) Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFFFFA500)))
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)),
                    CircleShape
                ), 
                Alignment.Center
            ) {
                Icon(
                    if (isNewAvailable) Icons.Default.NewReleases else Icons.Default.CheckCircle, 
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp)
                )
            }
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                if (isNewAvailable) "Premium Update Available" else "You're All Caught Up",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Current Version: $currentVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            if (isNewAvailable && update != null) {
                Spacer(Modifier.height(32.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Version ${update.version}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(update.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Download Size", style = MaterialTheme.typography.labelLarge)
                            Text(update.fileSize, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Spacer(Modifier.weight(1f))
                
                Button(
                    onClick = { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Update Now", fontSize = 18.sp)
                }
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    "BJM is running the latest premium version. We'll notify you when new features are ready!",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Back to Home")
                }
            }
        }
    }
}

@Composable
fun ShareAppDialog(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qrBitmap = remember(link) { QrUtils.generateQrCode(link) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Share BJM App", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Image(qrBitmap.asImageBitmap(), "Share QR", modifier = Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.height(16.dp))
                Text("Scan this QR code to download the app", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("BJM App Link", link))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Link")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
fun FriendItem(friend: Friend, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                val bitmap = remember(friend.profilePic) { ProfileUtils.decodeImage(friend.profilePic) }
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, modifier = Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Surface(Modifier.size(52.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (friend.isOnline) {
                    Box(Modifier.size(14.dp).align(Alignment.BottomEnd).background(Color(0xFF4CAF50), CircleShape).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(Color(0xFF4CAF50), CircleShape))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(friend.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    when { friend.isTyping -> "Typing..."; friend.isOnline -> "Active Now"; else -> "Offline" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (friend.isOnline || friend.isTyping) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    friend: Friend,
    messagesFlow: kotlinx.coroutines.flow.Flow<List<Message>>,
    onSend: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onSeen: () -> Unit,
    onBack: () -> Unit
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var initialLoadDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        if (messages.isNotEmpty() && !initialLoadDone) {
            delay(100); initialLoadDone = true; onSeen()
        }
    }

    // Comprehensive Auto-scroll to bottom
    LaunchedEffect(messages.size) { 
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Force scroll when keyboard height changes
    val isImeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && messages.isNotEmpty()) {
            delay(150) // Wait for layout stability
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(text) {
        if (text.isNotEmpty()) { 
            onTyping(true)
            delay(3000)
            onTyping(false) 
        } else { 
            onTyping(false) 
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0,0,0,0), 
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(friend.profilePic) { ProfileUtils.decodeImage(friend.profilePic) }
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        } else {
                            Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)) {
                                Icon(Icons.Default.Person, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(friend.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when { friend.isTyping -> "typing..."; friend.isOnline -> "Online"; else -> "Offline" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (friend.isOnline || friend.isTyping) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }, 
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState, 
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), 
                    contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp), // Increased padding
                    verticalArrangement = Arrangement.Bottom
                ) {
                    items(messages, key = { it.messageUuid.ifEmpty { it.id.toString() } }) { msg -> 
                        ChatBubble(msg, initialLoadDone)
                        Spacer(Modifier.height(6.dp)) 
                    }
                }
                
                // Top fading gradient
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                            )
                        )
                )

                // Bottom fading gradient
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(60.dp) // Height matches opaque part
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                startY = 0f,
                                endY = 100f
                            )
                        )
                )
            }

            // Stylish Input Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(28.dp)
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f))
                            ),
                            shape = RoundedCornerShape(28.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = text, 
                        onValueChange = { text = it }, 
                        placeholder = { Text("Type a message...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }, 
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, 
                            unfocusedContainerColor = Color.Transparent, 
                            focusedIndicatorColor = Color.Transparent, 
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                    )
                    
                    val sendScale = remember { Animatable(1f) }
                    
                    IconButton(
                        onClick = { 
                            if (text.isNotBlank()) { 
                                scope.launch {
                                    sendScale.animateTo(0.8f, tween(100))
                                    sendScale.animateTo(1.2f, spring(Spring.DampingRatioHighBouncy))
                                    sendScale.animateTo(1f, spring())
                                }
                                onSend(text)
                                text = "" 
                            } 
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary, 
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .size(44.dp)
                            .scale(sendScale.value)
                    ) { 
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(20.dp)) 
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: Message, shouldAnimate: Boolean) {
    val alignment = if (msg.isSentByMe) Alignment.End else Alignment.Start
    val bgColor = if (msg.isSentByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val textColor = if (msg.isSentByMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = if (msg.isSentByMe) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }
    
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeString = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        val entryTransition = remember { Animatable(if (shouldAnimate) 0f else 1f) }
        val offsetX = remember { Animatable(if (shouldAnimate) (if (msg.isSentByMe) 50f else -50f) else 0f) }
        
        LaunchedEffect(Unit) { 
            if (shouldAnimate) {
                launch { entryTransition.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
                launch { offsetX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
            }
        }
        
        Surface(
            color = bgColor, 
            shape = shape, 
            tonalElevation = if (msg.isSentByMe) 0.dp else 1.dp, 
            modifier = Modifier
                .graphicsLayer(
                    alpha = entryTransition.value,
                    scaleX = 0.8f + (entryTransition.value * 0.2f),
                    scaleY = 0.8f + (entryTransition.value * 0.2f),
                    translationX = offsetX.value
                )
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(msg.content, color = textColor, fontSize = 15.sp, lineHeight = 18.sp)
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        timeString, 
                        fontSize = 10.sp, 
                        color = textColor.copy(alpha = 0.6f)
                    )
                    if (msg.isSentByMe) {
                        val icon = when (msg.status) {
                            MessageStatus.PENDING -> Icons.Default.AccessTime
                            MessageStatus.SENT -> Icons.Default.Check
                            MessageStatus.DELIVERED, MessageStatus.SEEN -> Icons.Default.DoneAll
                        }
                        Icon(
                            icon, null, 
                            Modifier.size(12.dp), 
                            tint = if (msg.status == MessageStatus.SEEN) Color(0xFF42A5F5) else textColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(myId: String, onIdScanned: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val qrBitmap = remember(myId) { QrUtils.generateQrCode(myId) }
    
    var hasCamPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamPerm = it }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            try {
                val image = InputImage.fromFilePath(context, it)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        barcodes.firstNotNullOfOrNull { b -> b.rawValue }?.let(onIdScanned) ?: Toast.makeText(context, "No QR code found in photo", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { Toast.makeText(context, "Failed to scan photo", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { if (!hasCamPerm) launcher.launch(Manifest.permission.CAMERA) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Friend") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Scan from Photo")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (hasCamPerm) {
                    AndroidView({ ctx ->
                        val pv = PreviewView(ctx)
                        val camProvFut = ProcessCameraProvider.getInstance(ctx)
                        camProvFut.addListener({
                            val camProv = camProvFut.get()
                            val preview = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
                            val imgAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                            imgAnalysis.setAnalyzer(cameraExecutor) { imgProxy -> processImageProxy(scanner, imgProxy, onIdScanned) }
                            try { camProv.unbindAll(); camProv.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imgAnalysis) } catch (e: Exception) { Log.e("Scanner", "Binding failed", e) }
                        }, ContextCompat.getMainExecutor(ctx))
                        pv
                    }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) { Box(Modifier.size(260.dp).align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color.Transparent).background(MaterialTheme.colorScheme.background.copy(alpha = 0.1f))) }
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Camera permission required") }
            }
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your ID QR Code", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp)); Text("Let your friend scan this to connect", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp))
                    Image(qrBitmap.asImageBitmap(), "My QR", Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(currentName: String, currentPicBase64: String?, onSave: (String, String?) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    var picBase64 by remember { mutableStateOf(currentPicBase64) }
    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT < 28) {
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            } else {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                android.graphics.ImageDecoder.decodeBitmap(source)
            }
            picBase64 = ProfileUtils.encodeImage(bitmap)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Edit Profile") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = { TextButton(onClick = { if (name.isNotBlank()) onSave(name, picBase64) }) { Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp).clickable { galleryLauncher.launch("image/*") }) {
                val bitmap = remember(picBase64) { ProfileUtils.decodeImage(picBase64) }
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) { Icon(Icons.Default.AddAPhoto, null, Modifier.size(40.dp)) }
                Surface(Modifier.size(36.dp).align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Edit, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            }
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(Modifier.height(16.dp))
            Text("This information will be shared with your friends automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy, onIdScanned: (String) -> Unit) {
    imageProxy.image?.let { mediaImage ->
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image).addOnSuccessListener { barcodes -> barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onIdScanned) }.addOnCompleteListener { imageProxy.close() }
    } ?: imageProxy.close()
}
