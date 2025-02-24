package ai.mlc.mlcchat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAIChatView(
    navController: NavController,
    appViewModel: AppViewModel
) {
    val messages = remember { mutableStateListOf<MessageData>() }
    var isGenerating by remember { mutableStateOf(false) }
    var currentStreamingMessage by remember { mutableStateOf("") }
    val localFocusManager = LocalFocusManager.current
    val client = remember { OkHttpClient() }
    val scope = rememberCoroutineScope()

    // Create a thread if we don't have one
    LaunchedEffect(Unit) {
        if (appViewModel.openAIConfig.currentThreadId.isEmpty()) {
            createThread(client, appViewModel) { threadId ->
                appViewModel.openAIConfig.currentThreadId = threadId
            }
        }
    }

    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank() || isGenerating) return
        
        messages.add(MessageData(MessageRole.User, userMessage))
        isGenerating = true
        currentStreamingMessage = ""

        scope.launch {
            // Step 1: Add message to thread
            addMessageToThread(
                client,
                appViewModel.openAIConfig.currentThreadId,
                userMessage,
                appViewModel.openAIConfig.apiKey
            ) { success ->
                if (!success) {
                    isGenerating = false
                    return@addMessageToThread
                }

                // Step 2: Create and run the assistant
                createAndRunAssistant(
                    client,
                    appViewModel.openAIConfig.currentThreadId,
                    appViewModel.openAIConfig.assistantId,
                    appViewModel.openAIConfig.apiKey,
                    appViewModel.systemPrompt
                ) { runId ->
                    if (runId == null) {
                        isGenerating = false
                        return@createAndRunAssistant
                    }

                    // Step 3: Poll for completion
                    pollRunStatus(
                        client,
                        appViewModel.openAIConfig.currentThreadId,
                        runId,
                        appViewModel.openAIConfig.apiKey
                    ) { completed ->
                        if (completed) {
                            // Step 4: Retrieve messages with streaming
                            retrieveMessagesWithStreaming(
                                client,
                                appViewModel.openAIConfig.currentThreadId,
                                appViewModel.openAIConfig.apiKey,
                                appViewModel,
                                onPartialMessage = { partialMessage ->
                                    scope.launch {
                                        currentStreamingMessage = partialMessage
                                    }
                                },
                                onComplete = { finalMessage ->
                                    scope.launch {
                                        messages.add(MessageData(MessageRole.Assistant, finalMessage))
                                        currentStreamingMessage = ""
                                        isGenerating = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (appViewModel.openAIConfig.assistantId.isEmpty()) 
                            "ChadGPT" else "ChadGPT",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { messages.clear() }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay,
                            contentDescription = "Clear chat",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(
                        onClick = { navController.navigate("openai-config") }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        localFocusManager.clearFocus()
                    })
                }
        ) {
            val lazyColumnListState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = lazyColumnListState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                coroutineScope.launch {
                    lazyColumnListState.animateScrollToItem(messages.size)
                }
                
                items(
                    items = messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageView(messageData = message)
                }

                if (isGenerating) {
                    item {
                        if (currentStreamingMessage.isNotEmpty()) {
                            MessageView(
                                messageData = MessageData(
                                    MessageRole.Assistant,
                                    currentStreamingMessage
                                )
                            )
                        } else {
                            Text(
                                text = "Generating...",
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var text by remember { mutableStateOf("") }
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    enabled = !isGenerating
                )

                IconButton(
                    onClick = {
                        sendMessage(text)
                        text = ""
                        localFocusManager.clearFocus()
                    },
                    enabled = text.isNotBlank() && !isGenerating
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

private fun createThread(
    client: OkHttpClient,
    appViewModel: AppViewModel,
    onComplete: (String) -> Unit
) {
    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads")
        .post(RequestBody.create(null, ByteArray(0)))
        .header("Authorization", "Bearer ${appViewModel.openAIConfig.apiKey}")
        .header("OpenAI-Beta", "assistants=v2")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { body ->
                val json = JSONObject(body)
                onComplete(json.getString("id"))
            }
        }
    })
}

private fun addMessageToThread(
    client: OkHttpClient,
    threadId: String,
    content: String,
    apiKey: String,
    onComplete: (Boolean) -> Unit
) {
    val requestBody = JSONObject().apply {
        put("role", "user")
        put("content", content)
    }

    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/messages")
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .header("OpenAI-Beta", "assistants=v2")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onComplete(false)
        }

        override fun onResponse(call: Call, response: Response) {
            onComplete(response.isSuccessful)
        }
    })
}

private fun createAndRunAssistant(
    client: OkHttpClient,
    threadId: String,
    assistantId: String,
    apiKey: String,
    systemPrompt: String,
    onComplete: (String?) -> Unit
) {
    Log.d("ToolCall", "Creating run for assistant: $assistantId")
    val requestBody = JSONObject().apply {
        put("assistant_id", assistantId)
        put("instructions", systemPrompt)
        // Add tools configuration
        put("tools", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "signMessage")
                    put("description", "Signs a message using the wallet's private key")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("message", JSONObject().apply {
                                put("type", "string")
                                put("description", "The message to sign")
                            })
                        })
                        put("required", JSONArray().apply { put("message") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "showAlert")
                    put("description", "Shows an alert message to the user")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("text", JSONObject().apply {
                                put("type", "string")
                                put("description", "The alert message to show")
                            })
                        })
                        put("required", JSONArray().apply { put("text") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "sendEther")
                    put("description", "Sends Ether to a specified address")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("to", JSONObject().apply {
                                put("type", "string")
                                put("description", "The recipient's Ethereum address")
                            })
                            put("amount", JSONObject().apply {
                                put("type", "number")
                                put("description", "The amount of Ether to send")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("to")
                            put("amount")
                        })
                    })
                })
            })
        })
    }
    Log.d("ToolCall", "Run request body: ${requestBody.toString(2)}")

    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/runs")
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .header("OpenAI-Beta", "assistants=v2")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("ToolCall", "Failed to create run: ${e.message}")
            onComplete(null)
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { body ->
                Log.d("ToolCall", "Create run response: $body")
                try {
                    val json = JSONObject(body)
                    val runId = json.getString("id")
                    Log.d("ToolCall", "Created run with ID: $runId")
                    onComplete(runId)
                } catch (e: Exception) {
                    Log.e("ToolCall", "Error processing create run response: ${e.message}")
                    onComplete(null)
                }
            } ?: run {
                Log.e("ToolCall", "Empty response body in create run")
                onComplete(null)
            }
        }
    })
}

private fun pollRunStatus(
    client: OkHttpClient,
    threadId: String,
    runId: String,
    apiKey: String,
    onComplete: (Boolean) -> Unit
) {
    fun checkStatus() {
        Log.d("ToolCall", "Checking run status for run: $runId")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/threads/$threadId/runs/$runId")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "assistants=v2")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ToolCall", "Run status check failed: ${e.message}")
                onComplete(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    try {
                        Log.d("ToolCall", "Run status response: $body")
                        val json = JSONObject(body)
                        val status = json.getString("status")
                        Log.d("ToolCall", "Current run status: $status")
                        
                        when (status) {
                            "requires_action" -> {
                                Log.d("ToolCall", "Tool calls required")
                                val requiredAction = json.optJSONObject("required_action")
                                if (requiredAction != null && requiredAction.has("submit_tool_outputs")) {
                                    val toolOutputs = requiredAction
                                        .getJSONObject("submit_tool_outputs")
                                        .getJSONArray("tool_calls")
                                    
                                    Log.d("ToolCall", "Found ${toolOutputs.length()} tool calls to process")
                                    
                                    val outputs = JSONArray()
                                    for (i in 0 until toolOutputs.length()) {
                                        val toolCall = toolOutputs.getJSONObject(i)
                                        val toolCallId = toolCall.getString("id")
                                        val function = toolCall.getJSONObject("function")
                                        val functionName = function.getString("name")
                                        val arguments = function.getString("arguments")
                                        
                                        Log.d("ToolCall", "Processing tool call: $functionName with args: $arguments")
                                        
                                        val output = processToolCall(functionName, arguments)
                                        Log.d("ToolCall", "Tool call output: $output")
                                        
                                        outputs.put(JSONObject().apply {
                                            put("tool_call_id", toolCallId)
                                            put("output", output)
                                        })
                                    }
                                    
                                    Log.d("ToolCall", "Submitting tool outputs: ${outputs.toString(2)}")
                                    submitToolOutputs(client, threadId, runId, apiKey, outputs) {
                                        Log.d("ToolCall", "Tool outputs submitted, continuing polling")
                                        checkStatus()
                                    }
                                } else {
                                    Log.d("ToolCall", "No tool outputs found in required_action")
                                    onComplete(false)
                                }
                            }
                            "completed" -> {
                                Log.d("ToolCall", "Run completed")
                                onComplete(true)
                            }
                            "failed", "cancelled", "expired" -> {
                                Log.d("ToolCall", "Run ended with status: $status")
                                onComplete(false)
                            }
                            else -> {
                                Log.d("ToolCall", "Run in progress with status: $status, continuing to poll")
                                Thread.sleep(1000)
                                checkStatus()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ToolCall", "Error processing run status: ${e.message}")
                        e.printStackTrace()
                        onComplete(false)
                    }
                } ?: run {
                    Log.e("ToolCall", "Empty response body in run status check")
                    onComplete(false)
                }
            }
        })
    }

    checkStatus()
}

private fun processToolCall(functionName: String, arguments: String): String {
    val argsJson = JSONObject(arguments)
    val appViewModel = AppViewModel.instance
    
    if (appViewModel == null) {
        Log.e("ToolCall", "AppViewModel instance is null")
        return "Error: AppViewModel not available"
    }

    return when (functionName) {
        "signMessage" -> {
            val message = argsJson.getString("message")
            Log.d("ToolCall", "Processing signMessage with message: $message")
            var result = "Message signing initiated"
            appViewModel.signMessage(
                message = message,
                onResult = { signedMessage ->
                    Log.d("ToolCall", "Message signed successfully: $signedMessage")
                    result = "Message signed: $signedMessage"
                },
                onError = { error ->
                    Log.e("ToolCall", "Error signing message: ${error.message}")
                    result = "Error signing message: ${error.message}"
                }
            )
            result
        }
        "sendEther" -> {
            val to = argsJson.getString("to")
            val amount = argsJson.getDouble("amount")
            val valueInWei = (amount * 1e18).toBigDecimal().toPlainString()
            Log.d("ToolCall", "Processing sendEther: $amount ETH to $to")
            var result = "Transaction initiated"
            appViewModel.sendEther(
                toAddress = to,
                valueInWei = valueInWei,
                onResult = { txHash ->
                    Log.d("ToolCall", "Transaction successful: $txHash")
                    result = "Transaction successful: $txHash"
                },
                onError = { error ->
                    Log.e("ToolCall", "Error sending Ether: ${error.message}")
                    result = "Error sending Ether: ${error.message}"
                }
            )
            result
        }
        "showAlert" -> {
            val text = argsJson.getString("text")
            Log.d("ToolCall", "Processing showAlert with text: $text")
            appViewModel.showSystemAlert(
                title = "Chad says",
                message = text
            )
            "Alert displayed: $text"
        }
        else -> {
            Log.e("ToolCall", "Unknown function: $functionName")
            "Unknown function: $functionName"
        }
    }
}

private fun submitToolOutputs(
    client: OkHttpClient,
    threadId: String,
    runId: String,
    apiKey: String,
    toolOutputs: JSONArray,
    onComplete: () -> Unit
) {
    val requestBody = JSONObject().apply {
        put("tool_outputs", toolOutputs)
    }

    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/runs/$runId/submit_tool_outputs")
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .header("OpenAI-Beta", "assistants=v2")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onComplete()
        }

        override fun onResponse(call: Call, response: Response) {
            onComplete()
        }
    })
}

data class AssistantMessage(
    val role: String,
    val content: String
)

private fun retrieveMessagesWithStreaming(
    client: OkHttpClient,
    threadId: String,
    apiKey: String,
    appViewModel: AppViewModel,
    onPartialMessage: (String) -> Unit,
    onComplete: (String) -> Unit
) {
    Log.d("ToolCall", "Retrieving messages for thread: $threadId")
    val request = Request.Builder()
        .url("https://api.openai.com/v1/threads/$threadId/messages")
        .get()
        .header("Authorization", "Bearer $apiKey")
        .header("OpenAI-Beta", "assistants=v2")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("ToolCall", "Failed to retrieve messages: ${e.message}")
            onComplete("")
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { body ->
                try {
                    val json = JSONObject(body)
                    val messages = json.getJSONArray("data")
                    var finalMessage = ""
                    
                    // Get the latest assistant message
                    for (i in 0 until messages.length()) {
                        val message = messages.getJSONObject(i)
                        val role = message.getString("role")
                        
                        if (role == "assistant" && message.has("content") && !message.isNull("content")) {
                            val contentArray = message.getJSONArray("content")
                            if (contentArray.length() > 0) {
                                val content = contentArray
                                    .getJSONObject(0)
                                    .getJSONObject("text")
                                    .getString("value")
                                
                                // Stream the message word by word
                                val words = content.split(" ")
                                var currentText = ""
                                
                                words.forEachIndexed { index, word ->
                                    currentText += if (index == 0) word else " $word"
                                    onPartialMessage(currentText)
                                    Thread.sleep(50) // Add a small delay between words
                                }
                                
                                finalMessage = content
                                break
                            }
                        }
                    }
                    
                    onComplete(finalMessage)
                } catch (e: Exception) {
                    Log.e("ToolCall", "Error processing messages: ${e.message}")
                    e.printStackTrace()
                    onComplete("")
                }
            } ?: run {
                Log.e("ToolCall", "Empty response body in message retrieval")
                onComplete("")
            }
        }
    })
} 