package com.welivethisway.bots

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import com.welivethisway.bots.api.ChatImage
import com.welivethisway.bots.api.ImageFetch
import com.welivethisway.bots.api.ServerKind
import com.welivethisway.bots.api.decodeSampled
import java.util.concurrent.Executors

/** Full-screen view of one chat image. Tap the image (or back) to close. */
class ImageViewActivity : Activity() {

    private val bg = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_view)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = intent.getStringExtra("name") ?: "Image"

        val ivFull = findViewById<ImageView>(R.id.ivFull)
        ivFull.setOnClickListener { finish() }

        val image = ChatImage(
            kind = intent.getStringExtra("imageKind") ?: "",
            ref = intent.getStringExtra("ref") ?: "",
            name = intent.getStringExtra("name") ?: "image",
            mime = intent.getStringExtra("mime")
        )
        val threadId = intent.getStringExtra("threadId")

        bg.execute {
            try {
                val bytes = ImageFetch.bytes(this, image, threadId)
                val bitmap = decodeSampled(bytes, 2048)
                runOnUiThread {
                    if (bitmap != null) {
                        ivFull.setImageBitmap(bitmap)
                    } else {
                        Toast.makeText(this, "Could not open the image.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not open the image.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }

    companion object {
        fun open(activity: Activity, kind: ServerKind, image: ChatImage, botId: String, threadId: String?) {
            val intent = android.content.Intent(activity, ImageViewActivity::class.java).apply {
                putExtra("imageKind", image.kind)
                putExtra("ref", image.ref)
                putExtra("name", image.name)
                putExtra("mime", image.mime)
                putExtra("threadId", threadId)
            }
            activity.startActivity(intent)
        }
    }
}
