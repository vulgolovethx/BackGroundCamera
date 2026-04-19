package com.example.backgroundcamera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * MainActivity
 *
 * Tela principal com dois botões: Iniciar e Parar gravação.
 * Ao minimizar o app (ou abrir outro), a gravação continua
 * rodando via CameraRecordingService (Foreground Service).
 *
 * Ao terminar a gravação:
 * - O caminho do arquivo é exibido na tela
 * - Tocar no texto do caminho abre o vídeo no player
 * - Uma notificação também aparece com atalho para o vídeo
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvFilePath: TextView

    // URI do MediaStore do último vídeo gravado (para abrir no player)
    private var lastVideoUri: String? = null
    private var lastFilePath: String? = null

    // BroadcastReceiver para receber atualizações do Service
    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRecording = intent?.getBooleanExtra(
                CameraRecordingService.EXTRA_IS_RECORDING, false
            ) ?: false
            val filePath = intent?.getStringExtra(CameraRecordingService.EXTRA_FILE_PATH)
            val contentUri = intent?.getStringExtra(CameraRecordingService.EXTRA_CONTENT_URI)
            val error = intent?.getStringExtra(CameraRecordingService.EXTRA_ERROR)

            if (error != null) {
                updateUI(isRecording = false, error = error)
            } else {
                if (contentUri != null) lastVideoUri = contentUri
                if (filePath != null) lastFilePath = filePath
                updateUI(isRecording = isRecording, filePath = filePath)
            }
        }
    }

    // ─────────────────────────────────────────
    // Permissões
    // ─────────────────────────────────────────

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 e abaixo precisam de WRITE para gravar em pasta pública
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ precisa de READ_MEDIA_VIDEO para acessar vídeos
            add(Manifest.permission.READ_MEDIA_VIDEO)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissões concedidas!", Toast.LENGTH_SHORT).show()
            btnStart.isEnabled = true
        } else {
            Toast.makeText(
                this,
                "Permissões necessárias não concedidas. O app não funcionará.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ─────────────────────────────────────────
    // Ciclo de vida da Activity
    // ─────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvFilePath = findViewById(R.id.tvFilePath)

        btnStart.setOnClickListener { handleStartRecording() }
        btnStop.setOnClickListener { handleStopRecording() }

        // Toque no caminho do arquivo abre o vídeo no player
        tvFilePath.setOnClickListener { openLastVideo() }

        btnStart.isEnabled = false
        btnStop.isEnabled = false

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CameraRecordingService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(recordingReceiver)
    }

    // ─────────────────────────────────────────
    // Lógica de controle
    // ─────────────────────────────────────────

    private fun handleStartRecording() {
        if (!hasPermissions()) {
            checkAndRequestPermissions()
            return
        }
        // Limpa vídeo anterior ao iniciar nova gravação
        lastVideoUri = null
        lastFilePath = null
        tvFilePath.text = ""

        val intent = Intent(this, CameraRecordingService::class.java).apply {
            action = CameraRecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI(isRecording = true)
        Toast.makeText(this, "Gravação iniciada! Pode minimizar o app.", Toast.LENGTH_LONG).show()
    }

    private fun handleStopRecording() {
        val intent = Intent(this, CameraRecordingService::class.java).apply {
            action = CameraRecordingService.ACTION_STOP
        }
        startService(intent)
        updateUI(isRecording = false)
    }

    private fun updateUI(
        isRecording: Boolean,
        filePath: String? = null,
        error: String? = null
    ) {
        runOnUiThread {
            btnStart.isEnabled = !isRecording && hasPermissions()
            btnStop.isEnabled = isRecording

            when {
                error != null -> {
                    tvStatus.text = "❌ Erro: $error"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
                isRecording -> {
                    tvStatus.text = "🔴 Gravando em segundo plano..."
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
                else -> {
                    tvStatus.text = "⚪ Pronto para gravar"
                    tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                }
            }

            if (filePath != null) {
                val fileName = File(filePath).name
                // Indica ao usuário que pode tocar para abrir
                tvFilePath.text = "💾 Salvo: $fileName\n🎬 Toque aqui para abrir o vídeo"
                tvFilePath.isClickable = true
            }
        }
    }

    /**
     * Abre o último vídeo gravado no player padrão do dispositivo.
     * Usa o content:// URI do MediaStore quando disponível (mais compatível).
     */
    private fun openLastVideo() {
        val uri = lastVideoUri
        val path = lastFilePath

        if (uri == null && path == null) return

        val intent = when {
            uri != null -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            path != null -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(File(path)), "video/mp4")
            }
            else -> return
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Nenhum player de vídeo encontrado. Procure o arquivo em:\nMovies/BackgroundCam",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ─────────────────────────────────────────
    // Permissões
    // ─────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasPermissions()) {
            btnStart.isEnabled = true
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }
}
