package com.junichi.nfccarddemo

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var scannedText by mutableStateOf("")
    private var nfcStatus by mutableStateOf("NFC Status: Checking...")
    private var nfcStatusColor by mutableStateOf(Color.Gray)

    companion object {
        private const val TAG = "NFCScanner"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Initializing NFC")

        // NFCアダプターの初期化
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // NFCの状態を確認
        when {
            nfcAdapter == null -> {
                Log.e(TAG, "Device doesn't support NFC")
                nfcStatus = "このデバイスはNFCをサポートしていません"
                nfcStatusColor = Color.Red
                Toast.makeText(this, "このデバイスはNFCをサポートしていません", Toast.LENGTH_LONG).show()
            }
            !nfcAdapter!!.isEnabled -> {
                Log.w(TAG, "NFC is disabled")
                nfcStatus = "NFCが無効です - タップしてNFC設定を開く"
                nfcStatusColor = Color.Red
                Toast.makeText(this, "NFCを有効にしてください", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.d(TAG, "NFC is enabled and ready")
                nfcStatus = "NFCは有効です - カードをかざしてください"
                nfcStatusColor = Color.Green
            }
        }

        // インテントフィルターの設定
        val ndefIntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefIntentFilter.addDataType("*/*")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e(TAG, "Malformed mime type", e)
        }

        val intentFilters = arrayOf(
            ndefIntentFilter,
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )

        // PendingIntentの設定
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        Log.d(TAG, "PendingIntent created")

        setContent {
            NFCReaderScreen(
                scannedText = scannedText,
                nfcStatus = nfcStatus,
                nfcStatusColor = nfcStatusColor,
                onNfcSettingsClick = {
                    if (!nfcAdapter!!.isEnabled) {
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        nfcAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                Log.w(TAG, "NFC is disabled in onResume")
                nfcStatus = "NFCが無効です - タップしてNFC設定を開く"
                nfcStatusColor = Color.Red
            } else {
                Log.d(TAG, "Enabling NFC foreground dispatch")
                adapter.enableForegroundDispatch(
                    this,
                    pendingIntent,
                    arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)),
                    null
                )
                nfcStatus = "NFCは有効です - カードをかざしてください"
                nfcStatusColor = Color.Green
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: ${intent.action}")

        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> Log.d(TAG, "NDEF Discovered")
            NfcAdapter.ACTION_TECH_DISCOVERED -> Log.d(TAG, "Tech Discovered")
            NfcAdapter.ACTION_TAG_DISCOVERED -> Log.d(TAG, "Tag Discovered")
        }

        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {

            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            if (tag != null) {
                Log.d(TAG, "Tag detected: ${bytesToHexString(tag.id)}")
                processTag(tag)
            } else {
                Log.e(TAG, "Tag is null")
            }
        }
    }

    private fun processTag(tag: Tag) {
        Log.d(TAG, "Processing tag...")
        Log.d(TAG, "Available technologies: ${tag.techList.joinToString()}")

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Log.e(TAG, "NDEF not supported")
            runOnUiThread {
                scannedText = "このタグはNDEF形式ではありません"
                Toast.makeText(this, "このタグはNDEF形式ではありません", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            ndef.connect()
            Log.d(TAG, "Connected to tag")

            val ndefMessage = ndef.ndefMessage ?: ndef.cachedNdefMessage
            if (ndefMessage == null) {
                Log.e(TAG, "No NDEF messages found")
                runOnUiThread {
                    scannedText = "NDEFメッセージが見つかりません"
                }
                return
            }

            for ((index, record) in ndefMessage.records.withIndex()) {
                Log.d(TAG, "Record $index - TNF: ${record.tnf}, Type: ${String(record.type)}")
                val payload = record.payload
                val text = String(payload)
                Log.d(TAG, "Payload: $text")
                runOnUiThread {
                    scannedText = text
                    Toast.makeText(this, "データを読み取りました", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading tag", e)
            runOnUiThread {
                scannedText = "エラー: ${e.message}"
                Toast.makeText(this, "読み取りエラー", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try {
                ndef.close()
                Log.d(TAG, "Tag connection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing tag", e)
            }
        }
    }

    private fun bytesToHexString(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCReaderScreen(
    scannedText: String,
    nfcStatus: String,
    nfcStatusColor: Color,
    onNfcSettingsClick: () -> Unit
) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // NFC Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNfcSettingsClick
                ) {
                    Text(
                        text = nfcStatus,
                        color = nfcStatusColor,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // Scanned Data Card
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "スキャン結果",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (scannedText.isEmpty()) "未取得" else scannedText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}