package io.github.hakunm.deepseekharness

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.hakunm.deepseekharness.ui.GoApp
import io.github.hakunm.deepseekharness.ui.theme.DeepSeekHarnessTheme

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ChatNotifications.consume(intent)
        io.github.hakunm.deepseekharness.automation.AutomationScheduler(applicationContext).reconcile(io.github.hakunm.deepseekharness.automation.AutomationStore.get(applicationContext).state.value.tasks)
        setContent { DeepSeekHarnessTheme { GoApp() } }
    }
    override fun onStart() { super.onStart(); io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.foreground.value = true }
    override fun onStop() { io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.foreground.value = false; super.onStop() }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent)
        ChatNotifications.consume(intent)
        io.github.hakunm.deepseekharness.automation.AutomationNavigation.consume(intent)
    }
}
