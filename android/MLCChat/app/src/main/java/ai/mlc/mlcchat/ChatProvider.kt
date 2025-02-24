package ai.mlc.mlcchat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

class ChatProvider : ContentProvider() {
    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(ChatContract.AUTHORITY, ChatContract.PATH_CHAT, CHAT_URI)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != CHAT_URI) {
            throw IllegalArgumentException("Unknown URI: $uri")
        }

        // Get the prompt from selection args
        val prompt = selectionArgs?.firstOrNull() ?: return null
        
        // Get the AppViewModel instance
        val viewModel = AppViewModel.instance ?: return null
        
        // Create a CompletableFuture to handle the async response
        val future = CompletableFuture<String>()
        
        // Create a cursor with our columns
        val cursor = MatrixCursor(arrayOf(
            ChatContract.Columns.PROMPT,
            ChatContract.Columns.RESPONSE,
            ChatContract.Columns.MODEL_NAME,
            ChatContract.Columns.TIMESTAMP
        ))

        // Generate the response using the chat state
        runBlocking {
            viewModel.chatState.requestGenerate(prompt)
            // Wait for the response
            // Note: In a production app, you'd want to implement proper async handling
            Thread.sleep(1000) // Give time for the response to generate
            
            // Get the last assistant message
            val response = viewModel.chatState.messages.lastOrNull { 
                it.role == MessageRole.Assistant 
            }?.text ?: ""

            cursor.addRow(arrayOf(
                prompt,
                response,
                viewModel.chatState.modelName.value,
                System.currentTimeMillis()
            ))
        }

        return cursor
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CHAT_URI -> ChatContract.CONTENT_TYPE
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        private const val CHAT_URI = 1
    }
} 