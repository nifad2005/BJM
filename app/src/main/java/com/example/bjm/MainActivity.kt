package com.example.bjm

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bjm.ui.theme.BJMTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class Screen { Home, Scanner, Chat }

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

                Scaffold { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Home -> HomeScreen(
                                myId = mqttManager.getMyId(),
                                friendsFlow = dao.getAllFriends(),
                                onScanClick = { currentScreen = Screen.Scanner },
                                onFriendClick = { friend ->
                                    selectedFriend = friend
                                    currentScreen = Screen.Chat
                                }
                            )
                            Screen.Scanner -> ScannerScreen(
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
                                ChatScreen(
                                    friend = friend,
                                    messagesFlow = dao.getMessagesWithFriend(friend.id, mqttManager.getMyId()),
                                    onSend = { content -> mqttManager.sendMessage(friend.id, content) },
                                    onBack = { currentScreen = Screen.Home }
                                )
                            }
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

@Composable
fun HomeScreen(
    myId: String,
    friendsFlow: kotlinx.coroutines.flow.Flow<List<Friend>>,
    onScanClick: () -> Unit,
    onFriendClick: (Friend) -> Unit
) {
    val friends by friendsFlow.collectAsState(initial = emptyList())
    val qrBitmap = remember(myId) { QrUtils.generateQrCode(myId) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("My ID: $myId", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "My QR Code", modifier = Modifier.size(200.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onScanClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Scan Friend's QR")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Friends", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(friends) { friend ->
                ListItem(
                    headlineContent = { Text(friend.name) },
                    supportingContent = { Text(if (friend.isOnline) "Online" else "Offline", color = if (friend.isOnline) Color.Green else Color.Gray) },
                    modifier = Modifier.clickable { onFriendClick(friend) }
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
    onBack: () -> Unit
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(friend.name) }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        })
        LazyColumn(modifier = Modifier.weight(1f).padding(8.dp)) {
            items(messages) { msg ->
                val alignment = if (msg.isSent) Alignment.CenterEnd else Alignment.CenterStart
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
                    Card(modifier = Modifier.padding(4.dp)) {
                        Text(msg.content, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onIdScanned: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Scan QR Code") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        })
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val scanner = BarcodeScanning.getClient(
                            BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                        )

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(scanner, imageProxy, onIdScanned)
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                        } catch (e: Exception) {
                            Log.e("ScannerScreen", "Camera binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Camera permission required")
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onIdScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { 
                        onIdScanned(it)
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}
