@file:Suppress("FunctionName") // Composables use PascalCase by Compose convention

package com.medtroniclabs.microcoaching.sample

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import kotlinx.coroutines.launch

private val SpiceBlue = Color(0xFF2514BE)
private val SpiceBackground = Color(0xFFF2F9FC)
private val SpiceGreen = Color(0xFF1B6B4A)
private val SpiceLightGreen = Color(0xFFE8F5E9)

/**
 * Sample host activity — simulates the SPICE home screen.
 *
 * Layout mirrors the SPICE tile grid (Screening / Assessment / Enrollment / My Patients).
 * "Today's Coaching" card below the tiles demonstrates how UC-1 surfaces on the home screen
 * (equivalent to SPICE calling MicroCoachingSDK.onMorningOpen).
 *
 * Navigation drawer provides access to "Test AI Chat" — the old chat fragment kept for local
 * model testing. It is explicitly not part of the CHW-facing coaching flow.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ComposeView>(R.id.compose_root).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MicroCoachingTheme {
                    val context = LocalContext.current
                    SpiceHomeApp(
                        onLaunchCoaching = {
                            CoachingFlowActivity.launch(this@MainActivity, chwId = "sample_chw_001")
                        },
                        onLaunchChat = {
                            ChatTestActivity.launch(this@MainActivity)
                        },
                        onTileTap = { label ->
                            Toast.makeText(context, "$label — SPICE feature (not in demo)", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

// ── App shell (drawer + home) ─────────────────────────────────────────────────

@Composable
private fun SpiceHomeApp(
    onLaunchCoaching: () -> Unit,
    onLaunchChat: () -> Unit,
    onTileTap: (String) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SpiceDrawerContent(
                onHome = { scope.launch { drawerState.close() } },
                onTestChat = {
                    scope.launch { drawerState.close() }
                    onLaunchChat()
                },
            )
        },
    ) {
        SpiceHomeScreen(
            onMenuClick = { scope.launch { drawerState.open() } },
            onLaunchCoaching = onLaunchCoaching,
            onTileTap = onTileTap,
        )
    }
}

// ── Navigation drawer ─────────────────────────────────────────────────────────

@Composable
private fun SpiceDrawerContent(
    onHome: () -> Unit,
    onTestChat: () -> Unit,
) {
    ModalDrawerSheet {
        // Header — SPICE blue banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SpiceBlue)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Column {
                Text(
                    text = "Demo Site 2",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CHW",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
            label = { Text("Home") },
            selected = true,
            onClick = onHome,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(4.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null) },
            label = {
                Column {
                    Text("Test AI Chat")
                    Text(
                        text = "Local model testing",
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            },
            badge = {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF8C00).copy(alpha = 0.15f),
                ) {
                    Text(
                        text = "DEV",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF8C00),
                    )
                }
            },
            selected = false,
            onClick = onTestChat,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

// ── Home screen ───────────────────────────────────────────────────────────────

@Composable
private fun SpiceHomeScreen(
    onMenuClick: () -> Unit,
    onLaunchCoaching: () -> Unit,
    onTileTap: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpiceBackground),
    ) {
        // Toolbar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SpiceBlue)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Open menu",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Demo Site 2",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "CHW",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // 2×2 tile grid
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeScreenTile(
                            label = "SCREENING",
                            icon = Icons.Rounded.PersonSearch,
                            modifier = Modifier.weight(1f),
                            onClick = { onTileTap("Screening") },
                        )
                        HomeScreenTile(
                            label = "ASSESSMENT",
                            icon = Icons.AutoMirrored.Rounded.Assignment,
                            modifier = Modifier.weight(1f),
                            onClick = { onTileTap("Assessment") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeScreenTile(
                            label = "ENROLLMENT",
                            icon = Icons.Rounded.PersonAdd,
                            modifier = Modifier.weight(1f),
                            onClick = { onTileTap("Enrollment") },
                        )
                        HomeScreenTile(
                            label = "MY PATIENTS",
                            icon = Icons.Rounded.Group,
                            modifier = Modifier.weight(1f),
                            onClick = { onTileTap("My Patients") },
                        )
                    }
                }
            }

            item {
                TodaysCoachingCard(onStart = onLaunchCoaching)
            }
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun HomeScreenTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(56.dp),
                tint = SpiceBlue,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                color = SpiceBlue,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun TodaysCoachingCard(onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SpiceLightGreen),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.School,
                contentDescription = null,
                tint = SpiceGreen,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today's Coaching",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = SpiceGreen,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Morning learning card ready",
                    fontSize = 13.sp,
                    color = Color(0xFF444444),
                )
                Text(
                    text = "~3 min",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = SpiceGreen,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
