package app.vinkje

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import app.vinkje.reminders.Reminders
import app.vinkje.ui.HomeScreen
import app.vinkje.ui.ListScreen
import app.vinkje.ui.LoginScreen
import app.vinkje.ui.SmartList
import app.vinkje.ui.SmartListScreen
import app.vinkje.ui.TaskScreen
import app.vinkje.ui.AskNotificationPermission
import app.vinkje.ui.TodoTheme

class MainActivity : ComponentActivity() {
    /** A task to open, from a tapped reminder. */
    private val openTask = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeTask(intent)
        val app = application as TodoApp
        setContent {
            TodoTheme {
                val credentials by app.credentials.collectAsStateWithLifecycle()
                if (credentials == null) {
                    LoginScreen(onLoggedIn = app::logIn)
                } else {
                    AskNotificationPermission()
                    val nav = rememberNavController()
                    val pendingTask by openTask.collectAsStateWithLifecycle()
                    LaunchedEffect(pendingTask) {
                        pendingTask?.let { nav.navigate("task/$it"); openTask.value = null }
                    }
                    val loginExpired by app.loginExpired.collectAsStateWithLifecycle()
                    // Screens switch at once, as in To Do: no fade or slide in between.
                    NavHost(
                        nav,
                        startDestination = "home",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable("home") {
                            HomeScreen(
                                app.repository,
                                onOpenList = { nav.navigate("list/$it") },
                                onOpenSmart = { nav.navigate("smart/${it.name}") },
                                loginExpired = loginExpired,
                                onLogInAgain = { nav.navigate("login") },
                                onLogOut = app::logOut,
                            )
                        }
                        composable("login") {
                            LoginScreen(
                                onLoggedIn = { app.logIn(it); nav.popBackStack() },
                                initialServer = credentials?.server,
                                onCancel = { nav.popBackStack() },
                            )
                        }
                        composable("smart/{kind}") { entry ->
                            SmartListScreen(
                                app.repository,
                                kind = SmartList.valueOf(entry.arguments!!.getString("kind")!!),
                                onBack = { nav.popBackStack() },
                                onOpenTask = { nav.navigate("task/$it") },
                            )
                        }
                        composable("list/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            ListScreen(
                                app.repository,
                                listId = entry.arguments!!.getLong("id"),
                                onBack = { nav.popBackStack() },
                                onOpenTask = { nav.navigate("task/$it") },
                            )
                        }
                        composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            TaskScreen(app.repository, taskId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as TodoApp).apply { foreground = true; changes.clear() }
    }

    override fun onStop() {
        (application as TodoApp).foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeTask(intent)
    }

    private fun takeTask(intent: Intent?) {
        intent?.getLongExtra(Reminders.EXTRA_TASK, -1)?.takeIf { it > 0 }?.let { openTask.value = it }
    }
}
