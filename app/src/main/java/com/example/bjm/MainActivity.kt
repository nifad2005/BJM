package com.example.bjm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import java.util.concurrent.Executors

enum class Screen { Home, Scanner, Chat, Profile }

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        mqttManager = MqttManager(this, db.chatDao())
        mqttManager.connect()

        enableEdgeToEdge()
        setContent {
            BJMTheme {
                var currentScreen by remember { mutableStateOf(Screen.Home) }
                var selectedFriend by remember { mutableStateOf<Friend?>(null) }
                val scope = rememberCoroutineScope()
                val dao = remember { db.chatDao() }

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
                            if (targetState == Screen.Chat || targetState == Screen.Profile || targetState == Screen.Scanner) {
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
                                }
                            )
                            Screen.Scanner -> ScannerScreen(
                                myId = mqttManager.getMyId(),
                                onIdScanned = { id ->
                                    val newFriend = Friend(id, id)
                                    scope.launch {
                                        dao.insertFriend(newFriend)
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
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttManager.disconnect()
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
    onFriendClick: (Friend) -> Unit
) {
    val friends by friendsFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val driveLink = "https://drive.google.com/drive/folders/1b9P2QmmWab9kAAVL0thjHD2Cp3bAS8Uf?usp=sharing"
    var showShareDialog by remember { mutableStateOf(false) }

    if (showShareDialog) {
        ShareAppDialog(link = driveLink, onDismiss = { showShareDialog = false })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("BJM CHAT", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold)) },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share App") },
                            onClick = {
                                showShareDialog = true
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Update App") },
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(driveLink))
                                context.startActivity(intent)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.SystemUpdate, null) }
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
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
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
                Text(
                    "Share BJM App",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Share QR",
                    modifier = Modifier.size(200.dp).clip(RoundedCornerShape(16.dp))
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Scan this QR code to download the app",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("BJM App Link", link)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Link")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun FriendItem(friend: Friend, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                val bitmap = remember(friend.profilePic) { ProfileUtils.decodeImage(friend.profilePic) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (friend.isOnline) {
                    Box(modifier = Modifier.size(14.dp).align(Alignment.BottomEnd).background(Color(0xFF4CAF50), CircleShape).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(Color(0xFF4CAF50), CircleShape))
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(friend.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    when {
                        friend.isTyping -> "Typing..."
                        friend.isOnline -> "Active Now"
                        else -> "Offline"
                    },
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
    
    var initialLoadDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            if (!initialLoadDone) {
                delay(100)
                initialLoadDone = true
            }
            onSeen()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(friend.profilePic) { ProfileUtils.decodeImage(friend.profilePic) }
                        if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        } else {
                            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(friend.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    friend.isTyping -> "typing..."
                                    friend.isOnline -> "Online"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (friend.isOnline || friend.isTyping) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                items(messages, key = { it.id }) { msg -> 
                    ChatBubble(msg = msg, shouldAnimate = initialLoadDone)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Type a message...") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: Message, shouldAnimate: Boolean) {
    val horizontalAlignment = if (msg.isSentByMe) Alignment.End else Alignment.Start
    val bgColor = if (msg.isSentByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (msg.isSentByMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = if (msg.isSentByMe) RoundedCornerShape(18.dp, 18.dp, 2.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 2.dp)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment) {
        val scale = remember { Animatable(if (shouldAnimate) 0.7f else 1f) }
        LaunchedEffect(Unit) {
            if (shouldAnimate) {
                scale.animateTo(
                    1f, 
                    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }

        Surface(
            color = bgColor, 
            shape = shape, 
            tonalElevation = if (msg.isSentByMe) 0.dp else 2.dp,
            modifier = Modifier.scale(scale.value)
        ) {
            Text(msg.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = textColor, fontSize = 15.sp)
        }
        
        if (msg.isSentByMe) {
            val statusIcon = when (msg.status) {
                MessageStatus.PENDING -> Icons.Default.AccessTime
                MessageStatus.SENT -> Icons.Default.Check
                MessageStatus.DELIVERED -> Icons.Default.DoneAll
                MessageStatus.SEEN -> Icons.Default.DoneAll
            }
            val statusColor = if (msg.status == MessageStatus.SEEN) MaterialTheme.colorScheme.primary else Color.Gray
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                modifier = Modifier.size(14.dp).padding(top = 2.dp),
                tint = statusColor
            )
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
    
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    LaunchedEffect(Unit) { if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Friend") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(scanner, imageProxy, onIdScanned) }
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                                } catch (e: Exception) { Log.e("ScannerScreen", "Camera binding failed", e) }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) {
                        Box(modifier = Modifier.size(260.dp).align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color.Transparent).background(MaterialTheme.colorScheme.background.copy(alpha = 0.1f)))
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Camera permission required") }
                }
            }
            
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your ID QR Code", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Let your friend scan this to connect", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "My QR", modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    currentName: String,
    currentPicBase64: String?,
    onSave: (String, String?) -> Unit,
    onBack: () -> Unit
) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { if (name.isNotBlank()) onSave(name, picBase64) }) {
                        Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(120.dp).clickable { galleryLauncher.launch("image/*") }) {
                val bitmap = remember(picBase64) { ProfileUtils.decodeImage(picBase64) }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(40.dp))
                    }
                }
                Surface(modifier = Modifier.size(36.dp).align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("This information will be shared with your friends automatically when you connect or update.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy, onIdScanned: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image).addOnSuccessListener { barcodes ->
            for (barcode in barcodes) barcode.rawValue?.let { onIdScanned(it) }
        }.addOnCompleteListener { imageProxy.close() }
    } else imageProxy.close()
}
