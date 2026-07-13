package com.jellowbeanz.json

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }

        val title = TextView(this).apply {
            text = "Json — phone agent"
            textSize = 26f
        }
        status = TextView(this).apply {
            text = "Step 1: enable the accessibility service so Json can see and control the screen."
            textSize = 15f
            setPadding(0, 36, 0, 36)
        }

        val enableBtn = Button(this).apply {
            text = "Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val readBtn = Button(this).apply {
            text = "Test: read this screen"
            setOnClickListener {
                val service = JsonAccessibilityService.instance
                status.text = if (service == null) {
                    "Service isn't enabled yet — tap the button above first."
                } else {
                    val elements = service.readScreen()
                    buildString {
                        append("Json sees ${elements.size} elements. First few:\n\n")
                        elements.take(8).forEach { append("• ${it.label}\n") }
                    }
                }
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(enableBtn)
        root.addView(readBtn)
        setContentView(root)
    }
}
