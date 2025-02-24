package ai.mlc.mlcchat

import android.net.Uri

object ChatContract {
    const val AUTHORITY = "ai.mlc.mlcchat.provider"
    const val PATH_CHAT = "chat"
    
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_CHAT")
    
    // Column names for the chat content provider
    object Columns {
        const val PROMPT = "prompt"
        const val RESPONSE = "response"
        const val MODEL_NAME = "model_name"
        const val TIMESTAMP = "timestamp"
    }
    
    // MIME types
    const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_CHAT"
    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.$AUTHORITY.$PATH_CHAT"
} 