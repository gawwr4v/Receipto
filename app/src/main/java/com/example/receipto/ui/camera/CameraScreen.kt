package com.example.receipto.ui.camera

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.example.receipto.ocr.BarcodeScanner
import com.example.receipto.ocr.BarcodeScanResult
import com.example.receipto.ocr.OcrProcessor
import com.example.receipto.ocr.OcrResult
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.filled.*
import com.example.receipto.model.ParseResult
import com.example.receipto.model.ReceiptData
import com.example.receipto.model.ReceiptItem
import com.example.receipto.parser.ReceiptParser

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasCameraPermission = isGranted }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { capturedImageUri = it } }

    LaunchedEffect(Unit) {
        val permission = Manifest.permission.CAMERA
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCameraPermission = granted
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !hasCameraPermission -> PermissionDeniedScreen(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
            capturedImageUri != null -> ImagePreviewScreen(
                imageUri = capturedImageUri!!,
                onRetake = { capturedImageUri = null },
                onUseImage = {
                    android.widget.Toast.makeText(
                        context, "Image ready for OCR processing!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
            else -> CameraViewScreen(
                cameraProvider = cameraProvider,
                lifecycleOwner = lifecycleOwner,
                onImageCaptureReady = { imageCapture = it },
                isCapturing = isCapturing,
                onCaptureClick = {
                    isCapturing = true
                    captureImage(context, imageCapture) { uri ->
                        capturedImageUri = uri
                        isCapturing = false
                    }
                },
                onGalleryClick = { galleryLauncher.launch("image/*") }
            )
        }
    }

}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Camera Access Needed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "We need camera permission to scan receipts and tickets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
fun CameraViewScreen(
    cameraProvider: ProcessCameraProvider?,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onImageCaptureReady: (ImageCapture) -> Unit,
    isCapturing: Boolean,
    onCaptureClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(cameraProvider) {
        cameraProvider?.let {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            onImageCaptureReady(imageCapture)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                it.unbindAll()
                it.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Scan Receipt",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Position receipt within the frame",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(vertical = 32.dp, horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedIconButton(
                        onClick = onGalleryClick,
                        modifier = Modifier.size(64.dp),
                        border = BorderStroke(2.dp, Color.White),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = "Pick from gallery",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Button(
                        onClick = onCaptureClick,
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCapturing) Color.White.copy(alpha = 0.7f) else Color.White,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 4.dp
                        ),
                        enabled = !isCapturing
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Take photo",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(64.dp))
                }
            }
        }
        Box(
            modifier = Modifier.align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .aspectRatio(0.7f)
                .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        )
    }

}

@Composable
fun ParsedReceiptView(parseResult: ParseResult?, isProcessing: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isProcessing) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            when (parseResult) {
                is ParseResult.Success -> ReceiptDataCard(parseResult.data)
                is ParseResult.PartialSuccess -> {
                    ReceiptDataCard(parseResult.data)
                    WarningsCard(parseResult.warnings)
                }
                is ParseResult.Failure -> ErrorCard(parseResult.error)
                null -> Text("No parsing results yet")
            }
        }
    }
}

@Composable
fun ReceiptDataCard(receipt: ReceiptData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header with confidence score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Receipt Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = when {
                        receipt.getConfidenceScore() > 0.8f -> MaterialTheme.colorScheme.primary
                        receipt.getConfidenceScore() > 0.5f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "${(receipt.getConfidenceScore() * 100).toInt()}% confidence",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Store info
            receipt.storeName?.let {
                DataRow(icon = Icons.Default.Store, label = "Store", value = it)
            }

            receipt.storeAddress?.let {
                DataRow(icon = Icons.Default.LocationOn, label = "Address", value = it)
            }

            receipt.storePhone?.let {
                DataRow(icon = Icons.Default.Phone, label = "Phone", value = it)
            }

            // Date & Time
            Row(modifier = Modifier.fillMaxWidth()) {
                receipt.date?.let {
                    Column(modifier = Modifier.weight(1f)) {
                        DataRow(
                            icon = Icons.Default.CalendarToday,
                            label = "Date",
                            value = it.toString()
                        )
                    }
                }
                receipt.time?.let {
                    Column(modifier = Modifier.weight(1f)) {
                        DataRow(
                            icon = Icons.Default.AccessTime,
                            label = "Time",
                            value = it.toString()
                        )
                    }
                }
            }

            // Items section
            if (receipt.items.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    "Items (${receipt.items.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                receipt.items.forEach { item ->
                    ItemRow(item)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Totals section
            Divider(modifier = Modifier.padding(vertical = 16.dp))

            receipt.subtotal?.let {
                TotalRow("Subtotal", it, false)
            }

            receipt.tax?.let {
                TotalRow("Tax", it, false)
            }

            receipt.total?.let {
                TotalRow("Total", it, true)
            }

            // Payment & Transaction info
            if (receipt.paymentMethod != null || receipt.transactionId != null || receipt.cashier != null) {
                Divider(modifier = Modifier.padding(vertical = 16.dp))

                receipt.paymentMethod?.let {
                    DataRow(icon = Icons.Default.Payment, label = "Payment", value = it)
                }

                receipt.transactionId?.let {
                    DataRow(icon = Icons.Default.Tag, label = "Transaction", value = it)
                }

                receipt.cashier?.let {
                    DataRow(icon = Icons.Default.Person, label = "Cashier", value = it)
                }
            }
        }
    }
}

@Composable
fun DataRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ItemRow(item: ReceiptItem) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (item.quantity != null) {
                Text(
                    "Qty: ${item.quantity}${item.unit ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "$${String.format("%.2f", item.price)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TotalRow(label: String, amount: Double, isGrandTotal: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (isGrandTotal) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isGrandTotal) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            "$${String.format("%.2f", amount)}",
            style = if (isGrandTotal) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (isGrandTotal) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun WarningsCard(warnings: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Parsing Warnings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            warnings.forEach { warning ->
                Text(
                    "• $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
fun ErrorCard(error: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Parsing Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RawOcrView(ocrResult: OcrResult?, isProcessing: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (isProcessing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            when (ocrResult) {
                is OcrResult.Success -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Raw OCR Text",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem("Lines", ocrResult.lineCount.toString())
                                StatItem("Words", ocrResult.wordCount.toString())
                                StatItem("Confidence", "${(ocrResult.avgConfidence * 100).toInt()}%")
                            }
                            Divider(modifier = Modifier.padding(vertical = 16.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = ocrResult.fullText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
                is OcrResult.Error -> ErrorCard(ocrResult.message)
                null -> Text("No OCR results")
            }
        }
    }
}

@Composable
fun BarcodesView(barcodeResult: BarcodeScanResult?, isProcessing: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (isProcessing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Barcodes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight =

                                FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    when (barcodeResult) {
                        is BarcodeScanResult.Success -> {
                            barcodeResult.barcodes.forEachIndexed { index, barcode ->
                                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            barcode.format,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            barcode.displayValue,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        is BarcodeScanResult.NoBarcodes -> {
                            Text(
                                "No barcodes detected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is BarcodeScanResult.Error -> ErrorCard(barcodeResult.message)
                        null -> Text("No barcode results")
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreviewScreen(
    imageUri: Uri,
    onRetake: () -> Unit,
    onUseImage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isProcessing by remember { mutableStateOf(false) }
    var ocrResult by remember { mutableStateOf<OcrResult?>(null) }
    var barcodeResult by remember { mutableStateOf<BarcodeScanResult?>(null) }
    var parsedReceipt by remember { mutableStateOf<ParseResult?>(null) }
    var showResults by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) } // 0=Parsed, 1=Raw OCR, 2=Barcodes

    LaunchedEffect(imageUri) {
        isProcessing = true
        scope.launch {
            // Run OCR
            val ocrProcessor = OcrProcessor(context)
            val ocr = ocrProcessor.processImage(imageUri)
            ocrResult = ocr
            ocrProcessor.close()

            // Parse receipt if OCR succeeded
            if (ocr is OcrResult.Success) {
                val parser = ReceiptParser()
                parsedReceipt = parser.parse(ocr.fullText)
            }

            // Scan barcodes
            val barcodeScanner = BarcodeScanner(context)
            barcodeResult = barcodeScanner.scanImage(imageUri)
            barcodeScanner.close()

            isProcessing = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showResults) "Receipt Details" else "Preview",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row {
                    if (!showResults) {
                        IconButton(onClick = { showResults = true }) {
                            Icon(
                                Icons.Default.Article,
                                contentDescription = "Show results",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    IconButton(onClick = onRetake) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        if (showResults) {
            // Tabs for different views
            TabRow(
                selectedTabIndex = currentTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    text = { Text("Parsed Data") }
                )
                Tab(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    text = { Text("Raw OCR") }
                )
                Tab(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    text = { Text("Barcodes") }
                )
            }

            when (currentTab) {
                0 -> ParsedReceiptView(parsedReceipt, isProcessing)
                1 -> RawOcrView(ocrResult, isProcessing)
                2 -> BarcodesView(barcodeResult, isProcessing)
            }
        } else {
            // Image preview
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUri),
                        contentDescription = "Captured image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                if (isProcessing) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(64.dp),
                                color = Color.White,
                                strokeWidth = 6.dp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "Processing receipt...",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retake", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = { showResults = true },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isProcessing
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isProcessing) "Processing..." else "View Results",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
fun ResultsView(
    ocrResult: OcrResult?,
    barcodeResult: BarcodeScanResult?,
    isProcessing: Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isProcessing) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Article,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Extracted Text",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    when (ocrResult) {
                        is OcrResult.Success -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem("Lines", ocrResult.lineCount.toString())
                                StatItem("Words", ocrResult.wordCount.toString())
                                StatItem("Confidence", "${(ocrResult.avgConfidence * 100).toInt()}%")
                            }
                            Divider(modifier = Modifier.padding(vertical = 16.dp))
                            Text(
                                "Full Text:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = ocrResult.fullText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        is OcrResult.Error -> {
                            Text(
                                "Error: ${ocrResult.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        null -> Text("No OCR results yet")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null

                            ,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Barcodes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    when (barcodeResult) {
                        is BarcodeScanResult.Success -> {
                            barcodeResult.barcodes.forEachIndexed { index, barcode ->
                                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            barcode.format,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            barcode.displayValue,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        is BarcodeScanResult.NoBarcodes -> {
                            Text(
                                "No barcodes detected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is BarcodeScanResult.Error -> {
                            Text(
                                "Error: ${barcodeResult.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        null -> Text("No barcode scan results yet")
                    }
                }
            }
        }
    }
}

@Composable

fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun captureImage(
    context: android.content.Context,
    imageCapture: ImageCapture?,
    onImageCaptured: (Uri) -> Unit
) {
    if (imageCapture == null) return
    val photoFile = File(
        context.cacheDir,
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
    )
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageCaptured(Uri.fromFile(photoFile))
            }
            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
            }
        }
    )
}