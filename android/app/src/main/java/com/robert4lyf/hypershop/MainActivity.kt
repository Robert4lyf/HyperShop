package com.robert4lyf.hypershop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HyperShopApplication).container
        setContent {
            HyperShopTheme {
                HyperShopApp(container)
            }
        }
    }
}
