package ai.mlc.mlcchat

import ai.mlc.mlcchat.ui.theme.MLCChatTheme
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    @ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the intent if it matches our action
        handleIncomingIntent(intent)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize()
            ) {
                MLCChatTheme {
                    NavView()
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == "ai.mlc.GENERATE_RESPONSE") {
            val inputFd = intent.getParcelableExtra<ParcelFileDescriptor>("inputFd")
            val outputFd = intent.getParcelableExtra<ParcelFileDescriptor>("outputFd")

            if (inputFd != null && outputFd != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    processRequest(inputFd, outputFd)
                }
            } else {
                Toast.makeText(this, "Invalid file descriptors", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun processRequest(
        inputFd: ParcelFileDescriptor,
        outputFd: ParcelFileDescriptor
    ) {
        val userInput = withContext(Dispatchers.IO) {
            FileInputStream(inputFd.fileDescriptor).bufferedReader().use { it.readText() }
        }

        val aiResponse = "AI Response to: $userInput" // Replace with your actual AI response logic

        withContext(Dispatchers.IO) {
            FileOutputStream(outputFd.fileDescriptor).bufferedWriter().use { it.write(aiResponse) }
        }

        // Show the response in a Toast
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, aiResponse, Toast.LENGTH_SHORT).show()
        }
    }
}