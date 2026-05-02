package com.omarlatrach.counter

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarlatrach.counter.ui.theme.CounterTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class CounterScreen {
    Home,
    Counter,
    WarmUpCounter,
}

private enum class WorkoutPhase {
    IDLE,
    REP,
    REST,
    PAUSED,
}

enum class WarmUpStage {
    IDLE,
    WARMING_UP,
    COUNTING,
    STOPPED,
    COMPLETED,
}

private const val MinReps = 1
private const val MaxReps = 20
const val MaxWarmUpCountMinutes = 20

private val HomeBackground = Color(0xFFC62828)
private val WarmUpBackground = Color(0xFFF06292)
private val DurationOptions = listOf(10, 30, 60)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CounterTheme {
                CounterApp()
            }
        }
    }
}

@Composable
private fun CounterApp() {
    var currentScreen by rememberSaveable { mutableStateOf(CounterScreen.Home.name) }
    val screen = CounterScreen.valueOf(currentScreen)

    BackHandler(enabled = screen != CounterScreen.Home) {
        currentScreen = CounterScreen.Home.name
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            CounterScreen.Home -> HomeScreen(
                onCounterClick = { currentScreen = CounterScreen.Counter.name },
                onWarmUpCounterClick = { currentScreen = CounterScreen.WarmUpCounter.name },
            )

            CounterScreen.Counter -> CounterWorkoutScreen(
                onBack = { currentScreen = CounterScreen.Home.name },
            )

            CounterScreen.WarmUpCounter -> WarmUpCounterScreen(
                onBack = { currentScreen = CounterScreen.Home.name },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    onCounterClick: () -> Unit,
    onWarmUpCounterClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(32.dp))
            HomeButton(
                text = stringResource(R.string.counter_button),
                onClick = onCounterClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
            HomeButton(
                text = stringResource(R.string.warm_up_counter_button),
                onClick = onWarmUpCounterClick,
            )
        }
    }
}

@Composable
private fun HomeButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = HomeBackground,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CounterWorkoutScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPlayer = remember(context) { WorkoutAudioPlayer(context.applicationContext) }

    DisposableEffect(audioPlayer) {
        onDispose {
            audioPlayer.release()
        }
    }

    var repsInput by rememberSaveable { mutableStateOf("1") }
    var repDurationSeconds by rememberSaveable { mutableIntStateOf(DurationOptions.first()) }
    var silentCounting by rememberSaveable { mutableStateOf(false) }
    var hasRest by rememberSaveable { mutableStateOf(false) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var showSessionPanel by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var runToken by rememberSaveable { mutableIntStateOf(0) }
    var currentRep by rememberSaveable { mutableIntStateOf(0) }
    var secondsRemaining by rememberSaveable { mutableIntStateOf(0) }
    var phaseName by rememberSaveable { mutableStateOf(WorkoutPhase.IDLE.name) }
    var resumePhaseName by rememberSaveable { mutableStateOf(WorkoutPhase.REP.name) }

    val phase = WorkoutPhase.valueOf(phaseName)
    val activePhase = if (phase == WorkoutPhase.PAUSED) {
        WorkoutPhase.valueOf(resumePhaseName)
    } else {
        phase
    }
    val parsedReps = repsInput.toIntOrNull()
    val selectedReps = parsedReps?.takeIf { it in MinReps..MaxReps }
    val repCountForStatus = selectedReps ?: parsedReps?.coerceIn(MinReps, MaxReps) ?: 0
    val repsError = when {
        repsInput.isBlank() -> stringResource(R.string.reps_required_error)
        parsedReps == null -> stringResource(R.string.reps_required_error)
        parsedReps !in MinReps..MaxReps -> stringResource(R.string.reps_range_error)
        else -> null
    }

    val activeStatusTitle = when (activePhase) {
        WorkoutPhase.REP -> stringResource(R.string.rep_status_title, currentRep, repCountForStatus)
        WorkoutPhase.REST -> stringResource(
            R.string.rest_status_title,
            (currentRep + 1).coerceAtMost(repCountForStatus),
        )
        else -> stringResource(R.string.counter_title)
    }

    val statusTitle = if (phase == WorkoutPhase.PAUSED) {
        stringResource(R.string.paused_status_title)
    } else {
        activeStatusTitle
    }

    val statusDetail = if (phase == WorkoutPhase.PAUSED) {
        activeStatusTitle
    } else {
        stringResource(R.string.seconds_remaining_format, secondsRemaining)
    }

    fun resetSession(
        stopAudio: Boolean = true,
    ) {
        isRunning = false
        showSessionPanel = false
        showCancelConfirmation = false
        currentRep = 0
        secondsRemaining = 0
        phaseName = WorkoutPhase.IDLE.name
        resumePhaseName = WorkoutPhase.REP.name
        if (stopAudio) {
            audioPlayer.stop()
        }
    }

    fun startSession() {
        if (selectedReps == null) {
            return
        }

        audioPlayer.stop()
        currentRep = 1
        secondsRemaining = repDurationSeconds
        phaseName = WorkoutPhase.REP.name
        resumePhaseName = WorkoutPhase.REP.name
        showSessionPanel = true
        isRunning = true
        runToken += 1
    }

    fun pauseSession() {
        if (!isRunning) {
            return
        }

        audioPlayer.stop()
        resumePhaseName = phaseName
        phaseName = WorkoutPhase.PAUSED.name
        isRunning = false
    }

    fun resumeSession() {
        if (phase != WorkoutPhase.PAUSED) {
            return
        }

        phaseName = resumePhaseName
        isRunning = true
        runToken += 1
    }

    androidx.compose.runtime.LaunchedEffect(isRunning, runToken) {
        if (!isRunning || !showSessionPanel) {
            return@LaunchedEffect
        }

        val totalReps = selectedReps ?: return@LaunchedEffect
        val restDurationSeconds = repDurationSeconds / 2

        suspend fun runPhase() {
            while (secondsRemaining > 0) {
                delay(1000)
                secondsRemaining -= 1
            }
        }

        try {
            var rep = currentRep.coerceIn(1, totalReps)
            var runningPhase = activePhase

            while (rep <= totalReps) {
                if (runningPhase == WorkoutPhase.REP) {
                    currentRep = rep
                    phaseName = WorkoutPhase.REP.name
                    resumePhaseName = WorkoutPhase.REP.name
                    if (secondsRemaining !in 1..repDurationSeconds) {
                        secondsRemaining = repDurationSeconds
                    }
                    if (!silentCounting && secondsRemaining == repDurationSeconds) {
                        audioPlayer.playCount(rep)
                    }
                    runPhase()

                    if (hasRest && rep < totalReps) {
                        runningPhase = WorkoutPhase.REST
                        secondsRemaining = restDurationSeconds
                    } else {
                        rep += 1
                        if (rep <= totalReps) {
                            runningPhase = WorkoutPhase.REP
                            secondsRemaining = repDurationSeconds
                        }
                    }
                } else if (runningPhase == WorkoutPhase.REST) {
                    phaseName = WorkoutPhase.REST.name
                    resumePhaseName = WorkoutPhase.REST.name
                    if (secondsRemaining !in 1..restDurationSeconds) {
                        secondsRemaining = restDurationSeconds
                    }
                    if (!silentCounting && secondsRemaining == restDurationSeconds) {
                        audioPlayer.playBeep()
                    }
                    runPhase()
                    rep += 1
                    if (rep <= totalReps) {
                        runningPhase = WorkoutPhase.REP
                        secondsRemaining = repDurationSeconds
                    }
                }
            }

            audioPlayer.playFinished()
            resetSession(stopAudio = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    Scaffold(containerColor = HomeBackground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showSessionPanel) {
                TextButton(
                    onClick = {
                        audioPlayer.stop()
                        onBack()
                    },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(
                        text = stringResource(R.string.back_button),
                        color = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (showSessionPanel) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.16f),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = statusTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = statusDetail,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.timer_seconds_format, secondsRemaining),
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                if (phase == WorkoutPhase.PAUSED) {
                                    resumeSession()
                                } else {
                                    pauseSession()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = HomeBackground,
                            ),
                        ) {
                            Text(
                                text = stringResource(
                                    if (phase == WorkoutPhase.PAUSED) {
                                        R.string.resume_button
                                    } else {
                                        R.string.stop_button
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showCancelConfirmation = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.18f),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.cancel_button),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = repsInput,
                            onValueChange = { newValue ->
                                if (newValue.length <= 2 && newValue.all { it.isDigit() }) {
                                    repsInput = newValue
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRunning,
                            singleLine = true,
                            label = { Text(text = stringResource(R.string.reps_label)) },
                            supportingText = {
                                Text(
                                    text = repsError ?: stringResource(R.string.reps_support_text),
                                    color = if (repsError == null) {
                                        Color.White.copy(alpha = 0.82f)
                                    } else {
                                        Color(0xFFFFE0E0)
                                    },
                                )
                            },
                            isError = repsError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color.White.copy(alpha = 0.65f),
                                cursorColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.8f),
                                disabledBorderColor = Color.White.copy(alpha = 0.4f),
                                focusedLabelColor = Color.White,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.9f),
                                disabledLabelColor = Color.White.copy(alpha = 0.5f),
                                errorBorderColor = Color(0xFFFFE0E0),
                                errorLabelColor = Color(0xFFFFE0E0),
                                errorCursorColor = Color.White,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                            ),
                        )
                        Text(
                            text = stringResource(R.string.rep_duration_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        DurationSelector(
                            selectedDuration = repDurationSeconds,
                            enabled = !isRunning,
                            onSelect = { repDurationSeconds = it },
                        )
                        CheckboxRow(
                            checked = silentCounting,
                            enabled = !isRunning,
                            label = stringResource(R.string.silent_counting_label),
                            onCheckedChange = { silentCounting = it },
                        )
                        CheckboxRow(
                            checked = hasRest,
                            enabled = !isRunning,
                            label = stringResource(R.string.rest_between_reps_label),
                            onCheckedChange = { hasRest = it },
                        )
                        if (hasRest) {
                            Text(
                                text = stringResource(
                                    R.string.rest_duration_hint,
                                    repDurationSeconds / 2,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { startSession() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = repsError == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = HomeBackground,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.start_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text(text = stringResource(R.string.cancel_dialog_title)) },
            text = { Text(text = stringResource(R.string.cancel_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = { resetSession() },
                ) {
                    Text(text = stringResource(R.string.confirm_cancel_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelConfirmation = false },
                ) {
                    Text(text = stringResource(R.string.keep_counting_button))
                }
            },
        )
    }
}

@Composable
private fun DurationSelector(
    selectedDuration: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DurationButton(
            duration = DurationOptions[0],
            selectedDuration = selectedDuration,
            enabled = enabled,
            onSelect = onSelect,
        )
        DurationButton(
            duration = DurationOptions[1],
            selectedDuration = selectedDuration,
            enabled = enabled,
            onSelect = onSelect,
        )
        DurationButton(
            duration = DurationOptions[2],
            selectedDuration = selectedDuration,
            enabled = enabled,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun DurationButton(
    duration: Int,
    selectedDuration: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val selected = duration == selectedDuration

    Button(
        onClick = { onSelect(duration) },
        modifier = Modifier
            .widthIn(min = 76.dp)
            .height(44.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.White.copy(alpha = 0.14f),
            contentColor = if (selected) HomeBackground else Color.White,
        ),
    ) {
        Text(
            text = stringResource(R.string.duration_option_format, duration),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = Color.White,
                uncheckedColor = Color.White,
                checkmarkColor = HomeBackground,
                disabledCheckedColor = Color.White.copy(alpha = 0.55f),
                disabledUncheckedColor = Color.White.copy(alpha = 0.55f),
            ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun WarmUpCounterScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val warmUpState by WarmUpSessionStore.state.collectAsState()
    val stage = warmUpState.stage
    val showBackButton = stage != WarmUpStage.COUNTING
    val showSummary = stage == WarmUpStage.COUNTING ||
        stage == WarmUpStage.STOPPED ||
        stage == WarmUpStage.COMPLETED
    val displayedWarmUpSeconds = if (warmUpState.lockedWarmUpSeconds > 0) {
        warmUpState.lockedWarmUpSeconds
    } else {
        warmUpState.warmUpElapsedSeconds
    }

    Scaffold(containerColor = WarmUpBackground) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(WarmUpBackground)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            if (showBackButton) {
                TextButton(
                    onClick = {
                        if (stage == WarmUpStage.WARMING_UP ||
                            stage == WarmUpStage.STOPPED ||
                            stage == WarmUpStage.COMPLETED
                        ) {
                            WarmUpForegroundService.resetSession(context)
                        }
                        onBack()
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(
                        text = stringResource(R.string.back_button),
                        color = Color.White,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = {
                        when (stage) {
                            WarmUpStage.IDLE,
                            WarmUpStage.STOPPED,
                            WarmUpStage.COMPLETED,
                            -> WarmUpForegroundService.startWarmUp(context)

                            WarmUpStage.WARMING_UP -> WarmUpForegroundService.startCounter(context)
                            WarmUpStage.COUNTING -> WarmUpForegroundService.stopSession(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = WarmUpBackground,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            when (stage) {
                                WarmUpStage.IDLE,
                                WarmUpStage.STOPPED,
                                WarmUpStage.COMPLETED,
                                -> R.string.start_warm_up_button

                                WarmUpStage.WARMING_UP -> R.string.start_counter_button
                                WarmUpStage.COUNTING -> R.string.stop_button
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (stage == WarmUpStage.WARMING_UP) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.warm_up_music_running_hint),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (showSummary) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.16f),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(
                                when (stage) {
                                    WarmUpStage.COUNTING -> R.string.warm_up_counting_title
                                    WarmUpStage.COMPLETED -> R.string.warm_up_completed_title
                                    else -> R.string.warm_up_stopped_title
                                },
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        WarmUpSummaryRow(
                            label = stringResource(R.string.warm_up_time_label),
                            value = formatElapsedTime(displayedWarmUpSeconds),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        WarmUpSummaryRow(
                            label = stringResource(R.string.counting_time_label),
                            value = formatElapsedTime(warmUpState.countingElapsedSeconds),
                        )
                        if (stage == WarmUpStage.COUNTING) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(
                                    R.string.minutes_announced_format,
                                    warmUpState.lastAnnouncedMinute.coerceIn(0, MaxWarmUpCountMinutes),
                                    MaxWarmUpCountMinutes,
                                ),
                                color = Color.White.copy(alpha = 0.92f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarmUpSummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CounterAppPreview() {
    CounterTheme {
        HomeScreen(
            onCounterClick = {},
            onWarmUpCounterClick = {},
        )
    }
}

private class WorkoutAudioPlayer(
    private val context: Context,
) {
    private var mediaPlayer: MediaPlayer? = null

    fun playCount(rep: Int) {
        play(countSoundResForValue(rep))
    }

    fun playBeep() {
        play(R.raw.beep)
    }

    fun playFinished() {
        play(R.raw.finished)
    }

    fun stop() {
        mediaPlayer?.let { player ->
            player.setOnCompletionListener(null)
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            player.release()
        }
        mediaPlayer = null
    }

    fun release() {
        stop()
    }

    private fun play(resId: Int?) {
        stop()

        if (resId == null) {
            return
        }

        mediaPlayer = MediaPlayer.create(context, resId)?.apply {
            setOnCompletionListener { completedPlayer ->
                if (mediaPlayer === completedPlayer) {
                    mediaPlayer = null
                }
                completedPlayer.release()
            }
            start()
        }
    }

}

fun countSoundResForValue(rep: Int): Int? = when (rep) {
        1 -> R.raw.count_01
        2 -> R.raw.count_02
        3 -> R.raw.count_03
        4 -> R.raw.count_04
        5 -> R.raw.count_05
        6 -> R.raw.count_06
        7 -> R.raw.count_07
        8 -> R.raw.count_08
        9 -> R.raw.count_09
        10 -> R.raw.count_10
        11 -> R.raw.count_11
        12 -> R.raw.count_12
        13 -> R.raw.count_13
        14 -> R.raw.count_14
        15 -> R.raw.count_15
        16 -> R.raw.count_16
        17 -> R.raw.count_17
        18 -> R.raw.count_18
        19 -> R.raw.count_19
        20 -> R.raw.count_20
        else -> null
    }

fun formatElapsedTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
