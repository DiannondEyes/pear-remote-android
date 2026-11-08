package pear.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val ipInput: String = "",
    val portInput: String = "26538",
    val isConnected: Boolean = false,
    val isWaitingAuthApproval: Boolean = false,
    val statusText: String = "Не подключено",
    val trackTitle: String = "Нет трека",
    val trackArtist: String = "Неизвестный исполнитель",
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val volume: Float = 50f,
    val currentProgressSec: Long = 0L,
    val totalDurationSec: Long = 0L
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var volumeDebounceJob: Job? = null

    private val mediaNotificationManager = MediaNotificationManager(
        context = application.applicationContext,
        onTogglePlay = { togglePlay() },
        onNext = { next() },
        onPrevious = { previous() },
        onSeekTo = { seconds -> seekTo(seconds) }
    )

    private val remoteManager = PearRemoteManager(
        context = application.applicationContext,
        onStateUpdate = { remoteData ->
            _uiState.update { current ->
                val updated = current.copy(
                    isConnected = true,
                    isWaitingAuthApproval = false,
                    trackTitle = remoteData.title?.takeIf { it.isNotBlank() } ?: current.trackTitle,
                    trackArtist = remoteData.artist?.takeIf { it.isNotBlank() } ?: current.trackArtist,
                    coverUrl = if (remoteData.coverUrl != null) remoteData.coverUrl else current.coverUrl,
                    isPlaying = remoteData.isPlaying ?: current.isPlaying,
                    volume = remoteData.volume ?: current.volume,
                    currentProgressSec = remoteData.currentProgressSec ?: current.currentProgressSec,
                    totalDurationSec = remoteData.totalDurationSec ?: current.totalDurationSec
                )
                mediaNotificationManager.updateNotification(updated)
                updated
            }
        },
        onConnectionStatusChanged = { connected, statusMessage ->
            _uiState.update { current ->
                current.copy(
                    isConnected = connected,
                    statusText = statusMessage
                )
            }
        }
    )

    init {
        val savedIp = remoteManager.getSavedIp()
        val savedPort = remoteManager.getSavedPort()
        _uiState.update {
            it.copy(
                ipInput = savedIp,
                portInput = savedPort.toString()
            )
        }
    }

    fun onIpChanged(newIp: String) {
        _uiState.update { it.copy(ipInput = newIp) }
    }

    fun onPortChanged(newPort: String) {
        val filtered = newPort.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(portInput = filtered) }
    }

    fun connectToServer() {
        val ip = _uiState.value.ipInput.trim()
        val port = _uiState.value.portInput.toIntOrNull() ?: PearRemoteManager.DEFAULT_PORT

        if (ip.isEmpty()) {
            _uiState.update {
                it.copy(
                    isConnected = false,
                    isWaitingAuthApproval = false,
                    statusText = "Ошибка: укажите IP ПК"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWaitingAuthApproval = true,
                    statusText = "Ожидание подтверждения на ПК..."
                )
            }

            val result = remoteManager.connect(ip = ip, port = port)
            result.fold(
                onSuccess = {
                    _uiState.update { state ->
                        val updated = state.copy(
                            isConnected = true,
                            isWaitingAuthApproval = false,
                            statusText = "Подключено"
                        )
                        mediaNotificationManager.updateNotification(updated)
                        updated
                    }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        state.copy(
                            isConnected = false,
                            isWaitingAuthApproval = false,
                            statusText = error.message ?: "Ошибка подключения"
                        )
                    }
                }
            )
        }
    }

    fun togglePlay() {
        _uiState.update { current ->
            val updated = current.copy(isPlaying = !current.isPlaying)
            mediaNotificationManager.updateNotification(updated)
            updated
        }
        remoteManager.togglePlay()
    }

    fun next() {
        remoteManager.next()
    }

    fun previous() {
        remoteManager.previous()
    }

    fun onVolumeChanged(newVolume: Float) {
        val clamped = newVolume.coerceIn(0f, 100f)
        _uiState.update { it.copy(volume = clamped) }

        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(80L)
            remoteManager.setVolume(clamped.toInt())
        }
    }

    fun seekTo(seconds: Long) {
        val clamped = seconds.coerceAtLeast(0L)
        _uiState.update { current ->
            val updated = current.copy(currentProgressSec = clamped)
            mediaNotificationManager.updateNotification(updated)
            updated
        }
        remoteManager.seekTo(clamped)
    }

    override fun onCleared() {
        super.onCleared()
        remoteManager.disconnect()
        mediaNotificationManager.release()
    }
}
