package com.example.backgroundcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraRecordingService : Service() {

    companion object {
        private const val TAG = "CameraRecordingService"
        const val CHANNEL_ID = "camera_recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"
        const val BROADCAST_STATUS = "com.example.backgroundcamera.RECORDING_STATUS"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ERROR = "error"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String? = null
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gravação em segundo plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Câmera gravando em segundo plano"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, CameraRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📹 Gravando em segundo plano")
            .setContentText("Toque para abrir o app")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Parar gravação", stopPendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startRecording() {
    private fun createOutputFile(): String {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM
        ).also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(moviesDir, "VID_$timestamp.mp4")
        return file.absolutePath
    }
    }

    private fun createOutputFile(): String {
        val moviesDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "BackgroundCam"
            ).also { it.mkdirs() }
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(moviesDir, "VID_$timestamp.mp4")
        return file.absolutePath
    }
    private fun setupMediaRecorder(outputPath: String) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(1920, 1080)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(10_000_000)
            setOutputFile(outputPath)
            prepare()
        }
    }

    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("Câmera traseira não encontrada")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                broadcastError("Erro na câmera: código $error")
                stopSelf()
            }
        }, null)
    }

    private fun startCaptureSession() {
        val recorder = mediaRecorder ?: return
        val camera = cameraDevice ?: return
        val recorderSurface = recorder.surface
        val surfaces = listOf(recorderSurface)

        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD
                ).apply {
                    addTarget(recorderSurface)
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }.build()
                session.setRepeatingRequest(captureRequest, null, null)
                recorder.start()
                isRecording = true
                broadcastStatus(isRecording = true)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                broadcastError("Falha ao configurar sessão de câmera")
                stopSelf()
            }
        }, null)
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            cameraDevice?.close()
            cameraDevice = null
            broadcastStatus(isRecording = false, filePath = outputFilePath)
        } catch (e: Exception) {
            broadcastError("Erro ao parar: ${e.message}")
        }
    }

    private fun broadcastStatus(isRecording: Boolean, filePath: String? = null) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, isRecording)
            filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, false)
            putExtra(EXTRA_ERROR, message)
        }
        sendBroadcast(intent)
    }
}
