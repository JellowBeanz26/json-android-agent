package com.jellowbeanz.json

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply {
            text = "Json — phone agent\n\nBuild works. ✅"
            textSize = 22f
            setPadding(64, 128, 64, 64)
        }
        setContentView(label)
    }
}
