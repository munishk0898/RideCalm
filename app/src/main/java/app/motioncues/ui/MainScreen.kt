package app.motioncues.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as GraphicsIcon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motioncues.R
import app.motioncues.sensors.SensorController
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.CueSettings
import app.motioncues.settings.DotColor
import app.motioncues.settings.PatternStyle
import app.motioncues.tile.MotionCueTileService
import app.motioncues.util.Permissions

/** Snapshot of everything the screen needs to know about permissions and device support. */
private data class PermissionState(
    val overlay: Boolean,
    val notifications: Boolean,
    val activityRecognition: Boolean,
    val playServices: Boolean,
) {
    companion object {
        fun read(context: Context) = PermissionState(
            overlay = Permissions.canDrawOverlays(context),
            notifications = Permissions.notificationsEnabled(context),
            activityRecognition = Permissions.hasActivityRecognition(context),
            playServices = Permissions.isPlayServicesAvailable(context),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val loaded by viewModel.settings.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(PermissionState.read(context)) }
    val hasSensors = remember { SensorController(context).hasMotionSensors }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissions = PermissionState.read(context)
    }

    // A denied runtime permission can only be granted from system settings after the first ask.
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    var activityAsked by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAsked = true
        permissions = PermissionState.read(context)
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activityAsked = true
        permissions = PermissionState.read(context)
    }

    val requestNotifications = {
        if (Permissions.needsNotificationRuntimePermission() && !notificationAsked) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivitySafely(Permissions.notificationSettingsIntent(context))
        }
    }
    val requestActivity = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !activityAsked) {
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            context.startActivitySafely(Permissions.appDetailsIntent(context))
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val settings = loaded
        if (settings == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.screen_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SectionCard(title = stringResource(R.string.preview_title)) {
                    MotionPreview(settings = settings)
                }
            }

            if (!hasSensors) {
                item { WarningCard(text = stringResource(R.string.sensors_missing)) }
            }
            if (!permissions.overlay) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_overlay_title),
                        body = stringResource(R.string.perm_overlay_body),
                        actionLabel = stringResource(R.string.perm_open_settings),
                        onAction = { context.startActivitySafely(Permissions.overlaySettingsIntent(context)) },
                    )
                }
            }
            if (!permissions.notifications) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_notifications_title),
                        body = stringResource(R.string.perm_notifications_body),
                        actionLabel = stringResource(
                            if (Permissions.needsNotificationRuntimePermission() && !notificationAsked) {
                                R.string.perm_allow
                            } else {
                                R.string.perm_open_settings
                            },
                        ),
                        onAction = requestNotifications,
                    )
                }
            }
            if (!permissions.activityRecognition) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_activity_title),
                        body = stringResource(R.string.perm_activity_body),
                        actionLabel = stringResource(if (!activityAsked) R.string.perm_allow else R.string.perm_open_settings),
                        onAction = requestActivity,
                    )
                }
            }
            if (!permissions.playServices && settings.mode == ActivationMode.AUTOMATIC) {
                item {
                    WarningCard(
                        title = stringResource(R.string.perm_play_services_title),
                        text = stringResource(R.string.perm_play_services_body),
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.mode_title)) {
                    ModeSelector(
                        mode = settings.mode,
                        onSelect = { mode ->
                            viewModel.setMode(mode)
                            if (mode == ActivationMode.AUTOMATIC && !permissions.activityRecognition) requestActivity()
                            if (mode != ActivationMode.OFF && !permissions.notifications &&
                                Permissions.needsNotificationRuntimePermission() && !notificationAsked
                            ) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    Text(
                        text = stringResource(
                            when (settings.mode) {
                                ActivationMode.OFF -> R.string.mode_desc_off
                                ActivationMode.ON -> R.string.mode_desc_on
                                ActivationMode.AUTOMATIC -> R.string.mode_desc_automatic
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = { requestAddTile(context) }) {
                            Text(stringResource(R.string.tile_add))
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.pattern_title)) {
                    PatternSelector(
                        pattern = settings.pattern,
                        onSelect = { pattern -> viewModel.update { it.copy(pattern = pattern) } },
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.appearance_title)) {
                    Text(stringResource(R.string.color_title), style = MaterialTheme.typography.titleSmall)
                    ColorPicker(
                        selected = settings.color,
                        onSelect = { color -> viewModel.update { it.copy(color = color) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.adaptive_contrast),
                        subtitle = stringResource(R.string.adaptive_contrast_desc),
                        checked = settings.adaptiveContrast,
                        onCheckedChange = { value -> viewModel.update { it.copy(adaptiveContrast = value) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.larger_dots),
                        checked = settings.largerDots,
                        onCheckedChange = { value -> viewModel.update { it.copy(largerDots = value) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.more_dots),
                        checked = settings.moreDots,
                        onCheckedChange = { value -> viewModel.update { it.copy(moreDots = value) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.edge_rows),
                        subtitle = stringResource(R.string.edge_rows_desc),
                        checked = settings.edgeRows,
                        onCheckedChange = { value -> viewModel.update { it.copy(edgeRows = value) } },
                    )
                    SensitivitySlider(
                        value = settings.sensitivity,
                        onValueChange = { value -> viewModel.update { it.copy(sensitivity = value) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun WarningCard(text: String, title: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: ActivationMode, onSelect: (ActivationMode) -> Unit) {
    val options = listOf(
        ActivationMode.OFF to R.string.mode_off,
        ActivationMode.ON to R.string.mode_on,
        ActivationMode.AUTOMATIC to R.string.mode_automatic,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternSelector(pattern: PatternStyle, onSelect: (PatternStyle) -> Unit) {
    val options = listOf(
        PatternStyle.REGULAR to R.string.pattern_regular,
        PatternStyle.DYNAMIC to R.string.pattern_dynamic,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = pattern == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun ColorPicker(selected: DotColor, onSelect: (DotColor) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DotColor.entries.forEach { dotColor ->
            val isSelected = dotColor == selected
            val name = stringResource(dotColor.labelRes())
            val swatch = Color(dotColor.argb)
            val checkTint = if (dotColor == DotColor.WHITE) Color.Black else Color.White
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(
                        border = BorderStroke(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
                        shape = CircleShape,
                    )
                    .padding(5.dp)
                    .background(swatch, CircleShape)
                    .clickable(role = Role.RadioButton) { onSelect(dotColor) }
                    .semantics {
                        contentDescription = name
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SensitivitySlider(value: Float, onValueChange: (Float) -> Unit) {
    // Local state keeps dragging smooth; the value is persisted when the drag ends.
    var sliderValue by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.sensitivity), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.sensitivity_value, sliderValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue) },
            valueRange = CueSettings.MIN_SENSITIVITY..CueSettings.MAX_SENSITIVITY,
        )
    }
}

private fun DotColor.labelRes(): Int = when (this) {
    DotColor.WHITE -> R.string.color_white
    DotColor.BLACK -> R.string.color_black
    DotColor.GRAY -> R.string.color_gray
    DotColor.BLUE -> R.string.color_blue
    DotColor.ORANGE -> R.string.color_orange
    DotColor.GREEN -> R.string.color_green
}

@SuppressLint("NewApi")
private fun requestAddTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBar.requestAddTileService(
        ComponentName(context, MotionCueTileService::class.java),
        context.getString(R.string.tile_label),
        GraphicsIcon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { /* Result code is informational only. */ }
}

/** Some OEM builds lack a settings screen; fall back to the app's details page. */
private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        runCatching { startActivity(Permissions.appDetailsIntent(this)) }
    }
}
