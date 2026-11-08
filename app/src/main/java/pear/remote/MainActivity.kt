package pear.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val YtMusicBackground = Color(0xFF09090B)
private val YtMusicSurface = Color(0xFF16161A)
private val YtMusicSurfaceVariant = Color(0xFF222228)
private val YtMusicAccent = Color(0xFFFF0033)
private val YtMusicTextPrimary = Color(0xFFF5F5F7)
private val YtMusicTextSecondary = Color(0xFFA1A1AA)
private val StatusConnectedGreen = Color(0xFF22C55E)
private val StatusWaitingAmber = Color(0xFFF59E0B)
private val StatusErrorRed = Color(0xFFEF4444)

private val PearDarkColorScheme = darkColorScheme(
    primary = YtMusicAccent,
    onPrimary = Color.White,
    background = YtMusicBackground,
    onBackground = YtMusicTextPrimary,
    surface = YtMusicSurface,
    onSurface = YtMusicTextPrimary,
    surfaceVariant = YtMusicSurfaceVariant,
    onSurfaceVariant = YtMusicTextSecondary
)

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme(colorScheme = PearDarkColorScheme) {
                val state by viewModel.uiState.collectAsState()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = YtMusicBackground
                ) {
                    PearRemoteScreen(
                        state = state,
                        onIpChanged = viewModel::onIpChanged,
                        onPortChanged = viewModel::onPortChanged,
                        onConnectClick = viewModel::connectToServer,
                        onPreviousClick = viewModel::previous,
                        onPlayPauseClick = viewModel::togglePlay,
                        onNextClick = viewModel::next,
                        onVolumeChange = viewModel::onVolumeChanged,
                        onSeekTo = viewModel::seekTo
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun PearRemoteScreen(
    state: PlayerUiState,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnectClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. ВЕРХНЯЯ ПАНЕЛЬ ПОДКЛЮЧЕНИЯ
        ConnectionPanelCard(
            ip = state.ipInput,
            port = state.portInput,
            isConnected = state.isConnected,
            isWaitingAuth = state.isWaitingAuthApproval,
            statusText = state.statusText,
            onIpChanged = onIpChanged,
            onPortChanged = onPortChanged,
            onConnectClick = {
                focusManager.clearFocus()
                onConnectClick()
            }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // 2. ЦЕНТРАЛЬНАЯ ЧАСТЬ (ПЛЕЕР: ОБЛОЖКА, НАЗВАНИЕ, ПРОГРЕСС)
        PlayerCenterSection(
            trackTitle = state.trackTitle,
            trackArtist = state.trackArtist,
            coverUrl = state.coverUrl,
            currentProgressSec = state.currentProgressSec,
            totalDurationSec = state.totalDurationSec,
            onSeekTo = onSeekTo
        )

        Spacer(modifier = Modifier.height(18.dp))

        // 3. ПАНЕЛЬ УПРАВЛЕНИЯ ВОСПРОИЗВЕДЕНИЕМ
        PlaybackControlsSection(
            isPlaying = state.isPlaying,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 4. СЛАЙДЕР ГРОМКОСТИ
        VolumeControlCard(
            volume = state.volume,
            onVolumeChange = onVolumeChange
        )
    }
}

@Composable
private fun ConnectionPanelCard(
    ip: String,
    port: String,
    isConnected: Boolean,
    isWaitingAuth: Boolean,
    statusText: String,
    onIpChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = YtMusicSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Pear Desktop Remote",
                    color = YtMusicTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val indicatorColor = when {
                        isWaitingAuth -> StatusWaitingAmber
                        isConnected -> StatusConnectedGreen
                        else -> StatusErrorRed
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = YtMusicTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChanged,
                    modifier = Modifier.weight(1.8f),
                    label = { Text("IP ПК", fontSize = 12.sp) },
                    placeholder = { Text("192.168.1.xxx", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtMusicAccent,
                        unfocusedBorderColor = YtMusicSurfaceVariant,
                        focusedLabelColor = YtMusicAccent,
                        unfocusedLabelColor = YtMusicTextSecondary,
                        cursorColor = YtMusicAccent
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("Порт", fontSize = 12.sp) },
                    placeholder = { Text("26538", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtMusicAccent,
                        unfocusedBorderColor = YtMusicSurfaceVariant,
                        focusedLabelColor = YtMusicAccent,
                        unfocusedLabelColor = YtMusicTextSecondary,
                        cursorColor = YtMusicAccent
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onConnectClick,
                enabled = !isWaitingAuth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = YtMusicAccent,
                    contentColor = Color.White,
                    disabledContainerColor = YtMusicSurfaceVariant,
                    disabledContentColor = YtMusicTextSecondary
                )
            ) {
                if (isWaitingAuth) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ожидание подтверждения на ПК...", fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        text = if (isConnected) "Переподключиться" else "Подключиться",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCenterSection(
    trackTitle: String,
    trackArtist: String,
    coverUrl: String?,
    currentProgressSec: Long,
    totalDurationSec: Long,
    onSeekTo: (Long) -> Unit
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragProgressSec by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Большая квадратная обложка со скругленными углами
        Box(
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(YtMusicSurfaceVariant, YtMusicSurface)
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = YtMusicTextSecondary.copy(alpha = 0.35f),
                modifier = Modifier.size(88.dp)
            )

            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = trackTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Название песни (жирный крупный текст)
        Text(
            text = trackTitle,
            color = YtMusicTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Имя артиста
        Text(
            text = trackArtist,
            color = YtMusicTextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Ползунок прогресса трека (с отображением времени mm:ss)
        val safeDuration = totalDurationSec.coerceAtLeast(1L).toFloat()
        val displayedProgress = if (isDraggingSlider) {
            dragProgressSec.coerceIn(0f, safeDuration)
        } else {
            currentProgressSec.toFloat().coerceIn(0f, safeDuration)
        }

        Slider(
            value = if (totalDurationSec > 0L) displayedProgress else 0f,
            onValueChange = { value ->
                isDraggingSlider = true
                dragProgressSec = value
            },
            onValueChangeFinished = {
                isDraggingSlider = false
                if (totalDurationSec > 0L) {
                    onSeekTo(dragProgressSec.roundToLong())
                }
            },
            valueRange = 0f..safeDuration,
            enabled = totalDurationSec > 0L,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = YtMusicAccent,
                activeTrackColor = YtMusicAccent,
                inactiveTrackColor = YtMusicSurfaceVariant
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTimeMmSs(
                    if (isDraggingSlider) dragProgressSec.roundToLong() else currentProgressSec
                ),
                color = YtMusicTextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = formatTimeMmSs(totalDurationSec),
                color = YtMusicTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PlaybackControlsSection(
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPreviousClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(YtMusicSurfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Предыдущий трек",
                tint = YtMusicTextPrimary,
                modifier = Modifier.size(34.dp)
            )
        }

        FilledIconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier.size(78.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = YtMusicAccent,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                modifier = Modifier.size(42.dp)
            )
        }

        IconButton(
            onClick = onNextClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(YtMusicSurfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Следующий трек",
                tint = YtMusicTextPrimary,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun VolumeControlCard(
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = YtMusicSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (volume <= 0.5f) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeDown
                },
                contentDescription = "Тише",
                tint = YtMusicTextSecondary,
                modifier = Modifier.size(24.dp)
            )

            Slider(
                value = volume.coerceIn(0f, 100f),
                onValueChange = onVolumeChange,
                valueRange = 0f..100f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = YtMusicAccent,
                    inactiveTrackColor = YtMusicSurfaceVariant
                )
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Громче",
                tint = YtMusicTextSecondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${volume.roundToInt()}%",
                color = YtMusicTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(38.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

private fun formatTimeMmSs(seconds: Long): String {
    val safeSec = seconds.coerceAtLeast(0L)
    val mins = safeSec / 60L
    val secs = safeSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
