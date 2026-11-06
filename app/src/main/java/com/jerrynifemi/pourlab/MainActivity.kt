package com.jerrynifemi.pourlab

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var fluid: FluidView
    private val chips = HashMap<FluidView.Material, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lightStatusBar()

        fluid = findViewById(R.id.fluid)

        chips[FluidView.Material.WATER] = findViewById(R.id.chipWater)
        chips[FluidView.Material.SAND] = findViewById(R.id.chipSand)
        chips[FluidView.Material.BUBBLES] = findViewById(R.id.chipBubbles)
        chips[FluidView.Material.HONEY] = findViewById(R.id.chipHoney)

        chips.forEach { (material, chip) ->
            chip.setOnClickListener {
                fluid.material = material
                paintChips()
                fluid.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        paintChips()

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            fluid.refill(80)
            fluid.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        findViewById<View>(R.id.fabAdd).setOnLongClickListener {
            fluid.clearLiquid()
            true
        }

        val hint = findViewById<TextView>(R.id.hint)
        hint.postDelayed({
            hint.animate().alpha(0f).translationY(-dp(14).toFloat())
                .setDuration(520)
                .withEndAction { hint.visibility = View.GONE }
                .start()
        }, 5200)
    }

    private fun paintChips() {
        chips.forEach { (material, chip) ->
            val active = fluid.material == material
            chip.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
            chip.setTextColor(if (active) 0xFF161226.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun lightStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = 0xFFF6F3EE.toInt()
            window.navigationBarColor = 0xFFF6F3EE.toInt()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
