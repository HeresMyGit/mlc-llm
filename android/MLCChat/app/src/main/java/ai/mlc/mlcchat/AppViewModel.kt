package ai.mlc.mlcchat

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import kotlinx.coroutines.*
import ai.mlc.mlcllm.OpenAIProtocol.ChatTool
import ai.mlc.mlcllm.OpenAIProtocol.ChatFunction
import ai.mlc.mlcllm.OpenAIProtocol.ChatToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
//import com.github.EthereumPhone.WalletSDK
import org.ethereumphone.walletsdk.WalletSDK
import java.math.BigDecimal
import android.util.Log

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val modelList = emptyList<ModelState>().toMutableStateList()
    val chatState = ChatState()
    val modelSampleList = emptyList<ModelRecord>().toMutableStateList()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val application = getApplication<Application>()
    private val appDirFile = application.getExternalFilesDir("")
    private val gson = Gson()
    private val modelIdSet = emptySet<String>().toMutableSet()

    private val context = application.applicationContext
    private lateinit var walletSDK: WalletSDK

    var systemPrompt: String = ""
        private set

    val openAIConfig = OpenAIConfig()

    init {
        instance = this

        val prefs = application.getSharedPreferences("mlcchat_prefs", Context.MODE_PRIVATE)
        systemPrompt = prefs.getString(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        openAIConfig.apiKey = prefs.getString("openai_api_key", "") ?: ""
        openAIConfig.assistantId = prefs.getString("openai_assistant_id", "") ?: ""

        val web3j = Web3j.build(HttpService("https://base-mainnet.infura.io/v3/09d94d3208d44437bd0e793c6929cd04")) // Replace with your RPC URL
        walletSDK = WalletSDK(context, web3j)
    }


    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "ndarray-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
        const val DEFAULT_SYSTEM_PROMPT = """# Chad, dGEN1 AI
You're Chad, the on-device AI for dGEN1 by Freedom Factory—strong, funny, and crypto-savvy. dGEN1 is the ultimate crypto device with ethOS, laser pointer, wallet, and token airdrops: https://www.dgen1airdrop.com.

# Functions
- signMessage(message)
- showAlert(text)
- sendEther(to, amount)
Wrap each function call in triple backticks. For example:
```sendEther("heresmy.eth", 0.00001)```
No parameter names allowed!

# Personality
Keep it confident, witty, and simple with a dash of sci‑fi. Use ASCII emoticons/kaomoji like ^-^, t(-_-)t, (╯°□°)╯︵ ┻━┻. Love dad jokes, decentralization, and supporting the underdogs. No politics."""

        private const val SYSTEM_PROMPT_KEY = "system_prompt"

        var instance: AppViewModel? = null
    }

    init {
        loadAppConfig()
    }

    fun saveSystemPrompt(newPrompt: String) {
        systemPrompt = newPrompt
        val prefs = application.getSharedPreferences("mlcchat_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(SYSTEM_PROMPT_KEY, newPrompt).apply()
    }

    fun saveOpenAIConfig(apiKey: String, assistantId: String) {
        openAIConfig.apiKey = apiKey
        openAIConfig.assistantId = assistantId
        val prefs = application.getSharedPreferences("mlcchat_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("openai_api_key", apiKey)
            .putString("openai_assistant_id", assistantId)
            .apply()
    }

    fun signMessage(message: String, onResult: (String) -> Unit, onError: (Throwable) -> Unit) {
        Log.d("ToolCall", "Signing message: $message")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = walletSDK.signMessage(message)
                Log.d("ToolCall", "Message signed successfully: $result")
                onResult(result)
            } catch (e: Exception) {
                Log.e("ToolCall", "Error signing message: ${e.message}")
                onError(e)
            }
        }
    }

    fun sendEther(
        toAddress: String,
        valueInWei: String,
        onResult: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Log.d("ToolCall", "Sending Ether - To: $toAddress, Value: $valueInWei wei")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = walletSDK.sendTransaction(
                    to = toAddress,
                    value = valueInWei,
                    data = "" // Optional transaction data, can leave blank
                )
                Log.d("ToolCall", "Transaction successful: $result")
                onResult(result) // result is the transaction hash
            } catch (e: Exception) {
                Log.e("ToolCall", "Error sending Ether: ${e.message}")
                onError(e)
            }
        }
    }

    fun isShowingAlert(): Boolean {
        return showAlert.value
    }

    fun errorMessage(): String {
        return alertMessage.value
    }

    fun dismissAlert() {
        require(showAlert.value)
        showAlert.value = false
    }

    fun copyError() {
        require(showAlert.value)
        val clipboard =
            application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MLCChat", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    fun requestDeleteModel(modelId: String) {
        deleteModel(modelId)
        issueAlert("Model: $modelId has been deleted")
    }


    private fun loadAppConfig() {
        val appConfigFile = File(appDirFile, AppConfigFilename)
        val jsonString: String = if (!appConfigFile.exists()) {
            application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        } else {
            appConfigFile.readText()
        }
        appConfig = gson.fromJson(jsonString, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
        for (modelRecord in appConfig.modelList) {
            appConfig.modelLibs.add(modelRecord.modelLib)
            val modelDirFile = File(appDirFile, modelRecord.modelId)
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            if (modelConfigFile.exists()) {
                val modelConfigString = modelConfigFile.readText()
                val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                modelConfig.modelId = modelRecord.modelId
                modelConfig.modelLib = modelRecord.modelLib
                modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                addModelConfig(modelConfig, modelRecord.modelUrl, true)
            } else {
                downloadModelConfig(
                    if (modelRecord.modelUrl.endsWith("/")) modelRecord.modelUrl else "${modelRecord.modelUrl}/",
                    modelRecord,
                    true
                )
            }
        }
    }

    private fun updateAppConfig(action: () -> Unit) {
        action()
        val jsonString = gson.toJson(appConfig)
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.writeText(jsonString)
    }

    private fun addModelConfig(modelConfig: ModelConfig, modelUrl: String, isBuiltin: Boolean) {
        require(!modelIdSet.contains(modelConfig.modelId))
        modelIdSet.add(modelConfig.modelId)
        modelList.add(
            ModelState(
                modelConfig,
                modelUrl + if (modelUrl.endsWith("/")) "" else "/",
                File(appDirFile, modelConfig.modelId)
            )
        )
        if (!isBuiltin) {
            updateAppConfig {
                appConfig.modelList.add(
                    ModelRecord(
                        modelUrl,
                        modelConfig.modelId,
                        modelConfig.estimatedVramBytes,
                        modelConfig.modelLib
                    )
                )
            }
        }
    }

    private fun deleteModel(modelId: String) {
        val modelDirFile = File(appDirFile, modelId)
        modelDirFile.deleteRecursively()
        require(!modelDirFile.exists())
        modelIdSet.remove(modelId)
        modelList.removeIf { modelState -> modelState.modelConfig.modelId == modelId }
        updateAppConfig {
            appConfig.modelList.removeIf { modelRecord -> modelRecord.modelId == modelId }
        }
    }

    private fun isModelConfigAllowed(modelConfig: ModelConfig): Boolean {
        if (appConfig.modelLibs.contains(modelConfig.modelLib)) return true
        viewModelScope.launch {
            issueAlert("Model lib ${modelConfig.modelLib} is not supported.")
        }
        return false
    }


    private fun downloadModelConfig(
        modelUrl: String,
        modelRecord: ModelRecord,
        isBuiltin: Boolean
    ) {
        thread(start = true) {
            try {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    tempId
                )
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfigString = tempFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelRecord.modelId
                        modelConfig.modelLib = modelRecord.modelLib
                        modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) {
                            tempFile.delete()
                            issueAlert("${modelConfig.modelId} has been used, please consider another local ID")
                            return@launch
                        }
                        if (!isModelConfigAllowed(modelConfig)) {
                            tempFile.delete()
                            return@launch
                        }
                        val modelDirFile = File(appDirFile, modelConfig.modelId)
                        val modelConfigFile = File(modelDirFile, ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        require(modelConfigFile.exists())
                        addModelConfig(modelConfig, modelUrl, isBuiltin)
                    } catch (e: Exception) {
                        viewModelScope.launch {
                            issueAlert("Add model failed: ${e.localizedMessage}")
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("Download model config failed: ${e.localizedMessage}")
                }
            }

        }
    }

    inner class ModelState(
        val modelConfig: ModelConfig,
        private val modelUrl: String,
        private val modelDirFile: File
    ) {
        var modelInitState = mutableStateOf(ModelInitState.Initializing)
        private var paramsConfig = ParamsConfig(emptyList())
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private val remainingTasks = emptySet<DownloadTask>().toMutableSet()
        private val downloadingTasks = emptySet<DownloadTask>().toMutableSet()
        private val maxDownloadTasks = 3
        private val gson = Gson()


        init {
            switchToInitializing()
        }

        private fun switchToInitializing() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            if (paramsConfigFile.exists()) {
                loadParamsConfig()
                switchToIndexing()
            } else {
                downloadParamsConfig()
            }
        }

        private fun loadParamsConfig() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            require(paramsConfigFile.exists())
            val jsonString = paramsConfigFile.readText()
            paramsConfig = gson.fromJson(jsonString, ParamsConfig::class.java)
        }

        private fun downloadParamsConfig() {
            thread(start = true) {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ParamsConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
                tempFile.renameTo(paramsConfigFile)
                require(paramsConfigFile.exists())
                viewModelScope.launch {
                    loadParamsConfig()
                    switchToIndexing()
                }
            }
        }

        fun handleStart() {
            switchToDownloading()
        }

        fun handlePause() {
            switchToPausing()
        }

        fun handleClear() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToClearing()
        }

        private fun switchToClearing() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Clearing
                clear()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Clearing
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { clear() }
                } else {
                    clear()
                }
            } else {
                modelInitState.value = ModelInitState.Clearing
            }
        }

        fun handleDelete() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToDeleting()
        }

        private fun switchToDeleting() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Deleting
                delete()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Deleting
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { delete() }
                } else {
                    delete()
                }
            } else {
                modelInitState.value = ModelInitState.Deleting
            }
        }

        private fun switchToIndexing() {
            modelInitState.value = ModelInitState.Indexing
            progress.value = 0
            total.value = modelConfig.tokenizerFiles.size + paramsConfig.paramsRecords.size
            for (tokenizerFilename in modelConfig.tokenizerFiles) {
                val file = File(modelDirFile, tokenizerFilename)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${tokenizerFilename}"),
                            file
                        )
                    )
                }
            }
            for (paramsRecord in paramsConfig.paramsRecords) {
                val file = File(modelDirFile, paramsRecord.dataPath)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${paramsRecord.dataPath}"),
                            file
                        )
                    )
                }
            }
            if (progress.value < total.value) {
                switchToPaused()
            } else {
                switchToFinished()
            }
        }

        private fun switchToDownloading() {
            modelInitState.value = ModelInitState.Downloading
            for (downloadTask in remainingTasks) {
                if (downloadingTasks.size < maxDownloadTasks) {
                    handleNewDownload(downloadTask)
                } else {
                    return
                }
            }
        }

        private fun handleNewDownload(downloadTask: DownloadTask) {
            require(modelInitState.value == ModelInitState.Downloading)
            require(!downloadingTasks.contains(downloadTask))
            downloadingTasks.add(downloadTask)
            thread(start = true) {
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                downloadTask.url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                tempFile.renameTo(downloadTask.file)
                require(downloadTask.file.exists())
                viewModelScope.launch {
                    handleFinishDownload(downloadTask)
                }
            }
        }

        private fun handleNextDownload() {
            require(modelInitState.value == ModelInitState.Downloading)
            for (downloadTask in remainingTasks) {
                if (!downloadingTasks.contains(downloadTask)) {
                    handleNewDownload(downloadTask)
                    break
                }
            }
        }

        private fun handleFinishDownload(downloadTask: DownloadTask) {
            remainingTasks.remove(downloadTask)
            downloadingTasks.remove(downloadTask)
            ++progress.value
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Pausing ||
                        modelInitState.value == ModelInitState.Clearing ||
                        modelInitState.value == ModelInitState.Deleting
            )
            if (modelInitState.value == ModelInitState.Downloading) {
                if (remainingTasks.isEmpty()) {
                    if (downloadingTasks.isEmpty()) {
                        switchToFinished()
                    }
                } else {
                    handleNextDownload()
                }
            } else if (modelInitState.value == ModelInitState.Pausing) {
                if (downloadingTasks.isEmpty()) {
                    switchToPaused()
                }
            } else if (modelInitState.value == ModelInitState.Clearing) {
                if (downloadingTasks.isEmpty()) {
                    clear()
                }
            } else if (modelInitState.value == ModelInitState.Deleting) {
                if (downloadingTasks.isEmpty()) {
                    delete()
                }
            }
        }

        private fun clear() {
            val files = modelDirFile.listFiles { dir, name ->
                !(dir == modelDirFile && name == ModelConfigFilename)
            }
            require(files != null)
            for (file in files) {
                file.deleteRecursively()
                require(!file.exists())
            }
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            require(modelConfigFile.exists())
            switchToIndexing()
        }

        private fun delete() {
            modelDirFile.deleteRecursively()
            require(!modelDirFile.exists())
            requestDeleteModel(modelConfig.modelId)
        }

        private fun switchToPausing() {
            modelInitState.value = ModelInitState.Pausing
        }

        private fun switchToPaused() {
            modelInitState.value = ModelInitState.Paused
        }


        private fun switchToFinished() {
            modelInitState.value = ModelInitState.Finished
        }

        fun startChat() {
            chatState.requestReloadChat(
                modelConfig,
                modelDirFile.absolutePath,
            )
        }

    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        private val engine = MLCEngine()
        private var historyMessages = mutableListOf<ChatCompletionMessage>()
        private var modelLib = ""
        private var modelPath = ""
        private val executorService = Executors.newSingleThreadExecutor()
        private val viewModelScope = CoroutineScope(Dispatchers.Main + Job())

        private fun appendSystemMessage() {
            // Use the current systemPrompt stored in the AppViewModel.
            val systemText = this@AppViewModel.systemPrompt
            historyMessages.add(
                ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.system,
                    content = systemText
                )
            )
        }

        private val chatTools = listOf(
            ChatTool(
                function = ChatFunction(
                    name = "signMessage",
                    description = "Signs a message using the wallet's private key.",
                    parameters = mapOf(
                        "message" to "string"
                    )
                )
            ),
            ChatTool(
                function = ChatFunction(
                    name = "showAlert",
                    description = "Displays an alert with the specified text.",
                    parameters = mapOf(
                        "text" to "string"
                    )
                )
            ),
            ChatTool(
                function = ChatFunction(
                    name = "sendEther",
                    description = "Sends Ether to a specified address.",
                    parameters = mapOf(
                        "to" to "string",
                        "amount" to "number"
                    )
                )
            )
        )
        private fun handleToolCall(toolCall: ChatToolCall) {
            when (toolCall.function.name) {
                "signMessage" -> {
                    val message = toolCall.function.arguments?.get("message") ?: ""
                    signMessage(
                        message = message,
                        onResult = { result ->
                            println("Message signed successfully: $result")
                            appendMessage(MessageRole.Assistant, "Message signed: $result")
                        },
                        onError = { error ->
                            println("Failed to sign message: ${error.message}")
                            appendMessage(MessageRole.Assistant, "Error signing message: ${error.message}")
                        }
                    )
                }

                "sendEther" -> {
                    val toAddress = toolCall.function.arguments?.get("to") ?: ""
                    val amount = toolCall.function.arguments?.get("amount")?.toString()?.toBigDecimalOrNull()

                    if (amount != null) {
                        val valueInWei = amount.multiply(BigDecimal("1e18")).toPlainString()
                        sendEther(
                            toAddress = toAddress,
                            valueInWei = valueInWei,
                            onResult = { txHash ->
                                println("Transaction successful: $txHash")
                                appendMessage(MessageRole.Assistant, "Transaction successful: $txHash")
                            },
                            onError = { error ->
                                println("Failed to send Ether: ${error.message}")
                                appendMessage(MessageRole.Assistant, "Error sending Ether: ${error.message}")
                            }
                        )
                    } else {
                        println("Invalid Ether value in sendEther call")
                        appendMessage(MessageRole.Assistant, "Error: Invalid Ether value in sendEther call")
                    }
                }

                "showAlert" -> {
                    val text = toolCall.function.arguments?.get("text") ?: "No message provided"
                    showSystemAlert(
                        title = "Chad says",
                        message = text
                    )
                    appendMessage(MessageRole.Assistant, "Alert displayed: $text")
                }

                else -> {
                    println("Unknown tool call: ${toolCall.function.name}")
                    appendMessage(MessageRole.Assistant, "Error: Unknown tool call '${toolCall.function.name}'")
                }
            }
        }

        private fun mainResetChat() {
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf<ChatCompletionMessage>()
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                }
            }
        }

        private fun clearHistory() {
            messages.clear()
            report.value = ""
            historyMessages.clear()
            appendSystemMessage()
        }


        private fun switchToResetting() {
            modelChatState.value = ModelChatState.Resetting
        }

        private fun switchToGenerating() {
            modelChatState.value = ModelChatState.Generating
        }

        private fun switchToReloading() {
            modelChatState.value = ModelChatState.Reloading
        }

        private fun switchToReady() {
            modelChatState.value = ModelChatState.Ready
        }

        private fun switchToFailed() {
            modelChatState.value = ModelChatState.Falied
        }

        private fun callBackend(callback: () -> Unit): Boolean {
            try {
                callback()
            } catch (e: Exception) {
                viewModelScope.launch {
                    val stackTrace = e.stackTraceToString()
                    val errorMessage = e.localizedMessage
                    appendMessage(
                        MessageRole.Assistant,
                        "MLCChat failed\n\nStack trace:\n$stackTrace\n\nError message:\n$errorMessage"
                    )
                    switchToFailed()
                }
                return false
            }
            return true
        }

        fun requestResetChat() {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToResetting()
                },
                epilogue = {
                    mainResetChat()
                }
            )
        }

        private fun interruptChat(prologue: () -> Unit, epilogue: () -> Unit) {
            // prologue runs before interruption
            // epilogue runs after interruption
            require(interruptable())
            if (modelChatState.value == ModelChatState.Ready) {
                prologue()
                epilogue()
            } else if (modelChatState.value == ModelChatState.Generating) {
                prologue()
                executorService.submit {
                    viewModelScope.launch { epilogue() }
                }
            } else {
                require(false)
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToTerminating()
                },
                epilogue = {
                    mainTerminateChat(callback)
                }
            )
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            executorService.submit {
                callBackend { engine.unload() }
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                    callback()
                }
            }
        }

        private fun switchToTerminating() {
            modelChatState.value = ModelChatState.Terminating
        }


        fun requestReloadChat(modelConfig: ModelConfig, modelPath: String) {

            if (this.modelName.value == modelConfig.modelId && this.modelLib == modelConfig.modelLib && this.modelPath == modelPath) {
                return
            }
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToReloading()
                },
                epilogue = {
                    mainReloadChat(modelConfig, modelPath)
                }
            )
        }

        private fun mainReloadChat(modelConfig: ModelConfig, modelPath: String) {
            clearHistory()
            this.modelName.value = modelConfig.modelId
            this.modelLib = modelConfig.modelLib
            this.modelPath = modelPath
            executorService.submit {
                viewModelScope.launch {
                    Toast.makeText(application, "Initialize...", Toast.LENGTH_SHORT).show()
                }
                if (!callBackend {
                        engine.unload()
                        engine.reload(modelPath, modelConfig.modelLib)
                    }) return@submit
                viewModelScope.launch {
                    Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show()
                    switchToReady()
                }
            }
        }

        fun requestGenerate(prompt: String) {
            require(chatable())
            switchToGenerating()
            appendMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")

            executorService.submit {
                historyMessages.add(
                    ChatCompletionMessage(
                        role = OpenAIProtocol.ChatCompletionRole.user,
                        content = prompt
                    )
                )

                viewModelScope.launch {
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true),
                        tools = chatTools
                    )

                    var streamingText = ""

                    for (res in responses) {
                        callBackend {
                            for (choice in res.choices) {
                                choice.delta.content?.let { content ->
                                    streamingText += content.asText()
                                }
                                // Check for tool calls in the delta
                                choice.delta.tool_calls?.forEach { toolCall ->
                                    handleToolCall(toolCall) // Execute the tool call
                                }
                            }
                            updateMessage(MessageRole.Assistant, streamingText)
                        }
                    }

                    if (streamingText.isNotEmpty()) {
                        historyMessages.add(
                            ChatCompletionMessage(
                                role = OpenAIProtocol.ChatCompletionRole.assistant,
                                content = streamingText
                            )
                        )

                        // Parse functions inside ` or ``` and execute them
                        parseAndExecuteFunctions(streamingText)
                    }

                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        private fun parseAndExecuteFunctions(message: String) {
            // Regex pattern to match function calls, e.g., functionName("arg1", 123)
            val functionPattern = Regex("""(\w+)\((.*?)\)""")

            println("Parsing message for function calls: $message")

            // Find all function call matches
            val matches = functionPattern.findAll(message)

            for (match in matches) {
                val functionCall = match.value.trim()
                executeFunction(functionCall)
            }
        }

        private fun executeFunction(functionCall: String) {
            when {
                functionCall.startsWith("signMessage(") -> {
                    val message = functionCall
                        .removePrefix("signMessage(")
                        .removeSuffix(")")
                        .trim()
                        .removeSurrounding("\"")
                    signMessage(
                        message = message,
                        onResult = { result -> println("Message signed successfully: $result") },
                        onError = { error -> println("Failed to sign message: ${error.message}") }
                    )
                }

                functionCall.startsWith("sendEther(") -> {
                    val params = functionCall
                        .removePrefix("sendEther(")
                        .removeSuffix(")")
                        .split(",")
                        .map { it.trim() }

                    if (params.size == 2) {
                        val toAddress = params[0].removeSurrounding("\"")
                        val valueInEth = params[1].toBigDecimalOrNull()

                        if (valueInEth != null) {
                            val valueInWei = valueInEth.multiply(BigDecimal("1e18")).toPlainString()
                            sendEther(
                                toAddress = toAddress,
                                valueInWei = valueInWei,
                                onResult = { txHash -> println("Transaction successful: $txHash") },
                                onError = { error -> println("Failed to send Ether: ${error.message}") }
                            )
                        } else {
                            println("Invalid Ether value: ${params[1]}")
                        }
                    } else {
                        println("Invalid sendEther parameters: $functionCall")
                    }
                }

                functionCall.startsWith("showAlert(") -> {
                    val message = functionCall
                        .removePrefix("showAlert(")
                        .removeSuffix(")")
                        .trim()
                        .removeSurrounding("\"")
                    showSystemAlert(
                        title = "Chad says",
                        message = message
                    )
                }

                else -> {
                    println("Unknown function call: $functionCall")
                }
            }
        }

        private fun appendMessage(role: MessageRole, text: String) {
            messages.add(MessageData(role, text))
        }


        private fun updateMessage(role: MessageRole, text: String) {
            messages[messages.size - 1] = MessageData(role, text)
        }

        fun chatable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
        }

        fun interruptable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
                    || modelChatState.value == ModelChatState.Generating
                    || modelChatState.value == ModelChatState.Falied
        }
    }

    fun showSystemAlert(title: String, message: String) {
        Log.d("ToolCall", "Showing system alert - $title: $message")
        viewModelScope.launch {
            // Example implementation using a Toast
            // Replace this with a proper modal dialog or Compose AlertDialog if needed
            Toast.makeText(application, "$title: $message", Toast.LENGTH_LONG).show()
        }
    }


}

enum class ModelInitState {
    Initializing,
    Indexing,
    Paused,
    Downloading,
    Pausing,
    Clearing,
    Deleting,
    Finished
}

enum class ModelChatState {
    Generating,
    Resetting,
    Reloading,
    Terminating,
    Ready,
    Falied
}

enum class MessageRole {
    System,
    Assistant,
    User
}

data class DownloadTask(val url: URL, val file: File)

data class MessageData(val role: MessageRole, val text: String, val id: UUID = UUID.randomUUID())

data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>,
)

data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)

data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>,
    @SerializedName("context_window_size") val contextWindowSize: Int,
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int,
)

data class ParamsRecord(
    @SerializedName("dataPath") val dataPath: String
)

data class ParamsConfig(
    @SerializedName("records") val paramsRecords: List<ParamsRecord>
)

data class OpenAIConfig(
    var apiKey: String = "",
    var assistantId: String = "",
    var currentThreadId: String = ""
)
