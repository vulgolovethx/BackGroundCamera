package com.example.backgroundcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.util.Size
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
        private const val NOTIFICATION_ID_SAVED = 1002

        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"

        const val BROADCAST_STATUS = "com.example.backgroundcamera.RECORDING_STATUS"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_CONTENT_URI = "content_uri"
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
        Log.d(TAG, "Service criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Recebido ACTION_START")
                startForegroundWithNotification()
                startRecording()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Recebido ACTION_STOP")
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
        Log.d(TAG, "Service destruído")
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CameraRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
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

    private fun showRecordingSavedNotification(filePath: String, videoUri: Uri?) {
        val openIntent = if (videoUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(videoUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ Vídeo salvo!")
            .setContentText(File(filePath).name)
            .setSubText("Toque para reproduzir")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_SAVED, notification)
        Log.d(TAG, "Notificação de vídeo salvo exibida")
    }

    private fun startRecording() {
        try {
            outputFilePath = createOutputFile()
            val maxVideoSize = getMaxSupportedVideoSize()
            setupMediaRecorder(outputFilePath!!, maxVideoSize)
            openCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar gravação: ${e.message}", e)
            broadcastError("Erro ao iniciar gravação: ${e.message}")
            stopSelf()
        }
    }

    private fun createOutputFile(): String {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "BackgroundCam"
        ).also { it.mkdirs() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(moviesDir, "VID_$timestamp.mp4")
        Log.d(TAG, "Arquivo de saída: ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * Busca a maior resolução de vídeo suportada pelo hardware da câmera traseira.
     */
    private fun getMaxSupportedVideoSize(): Size {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList[0]

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // Tenta obter a maior resolução suportada diretamente pela classe MediaRecorder
        val choices = map?.getOutputSizes(MediaRecorder::class.java)
        val maxSize = choices?.maxByOrNull { it.width * it.height }

        return maxSize ?: Size(1920, 1080) // Fallback seguro caso não encontre
    }

    private fun setupMediaRecorder(outputPath: String, videoSize: Size) {
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
            
            // Define dinamicamente a maior resolução encontrada
            setVideoSize(videoSize.width, videoSize.height)
            setVideoFrameRate(30)
            
            // Aumentado para 30 Mbps (ideal para 1080p60 / 4K com altíssima qualidade de imagem)
            setVideoEncodingBitRate(30_000_000)
            
            setOutputFile(outputPath)
            prepare()
        }

        Log.d(TAG, "MediaRecorder configurado com resolução máxima: ${videoSize.width}x${videoSize.height}")
    }

    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("Câmera traseira não encontrada")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Câmera aberta com sucesso")
                cameraDevice = camera
                startCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Câmera desconectada")
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Erro na câmera: $error")
                camera.close(); cameraDevice = null
                broadcastError("Erro na câmera: código $error")
                stopSelf()
            }
        }, null)
    }

    private fun startCaptureSession() {
        val recorder = mediaRecorder ?: return
        val camera = cameraDevice ?: return
        val recorderSurface = recorder.surface

        camera.createCaptureSession(
            listOf(recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Sessão configurada, iniciando gravação")
                    captureSession = session

                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                        
                        // Estabilização de vídeo ativada
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                        // Força Autofoco Contínuo para gravação de vídeo
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                    }.build()

                    session.setRepeatingRequest(captureRequest, null, null)
                    recorder.start()
                    isRecording = true
                    broadcastStatus(isRecording = true)
                    Log.d(TAG, "✅ Gravação iniciada com qualidade máxima!")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Falha ao configurar sessão")
                    broadcastError("Falha ao configurar sessão de câmera")
                    stopSelf()
                }
            },
            null
        )
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

            Log.d(TAG, "✅ Gravação salva em: $outputFilePath")

            outputFilePath?.let { indexVideoInGallery(it) }
                ?: broadcastStatus(isRecording = false)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar gravação: ${e.message}", e)
            broadcastError("Erro ao parar: ${e.message}")
        }
    }

    private fun indexVideoInGallery(filePath: String) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(filePath),
            arrayOf("video/mp4")
        ) { scannedPath, contentUri ->
            Log.d(TAG, "Vídeo indexado: $scannedPath | URI: $contentUri")

            broadcastStatus(
                isRecording = false,
                filePath = scannedPath ?: filePath,
                contentUri = contentUri?.toString()
            )

            showRecordingSavedNotification(scannedPath ?: filePath, contentUri)
        }
    }

    private fun broadcastStatus(
        isRecording: Boolean,
        filePath: String? = null,
        contentUri: String? = null
    ) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, isRecording)
            filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
            contentUri?.let { putExtra(EXTRA_CONTENT_URI, it) }
        })
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, false)
            putExtra(EXTRA_ERROR, message)
        })
    }
}
        val pendingIntent = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ Vídeo salvo!")
            .setContentText(File(filePath).name)
            .setSubText("Toque para reproduzir")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_SAVED, notification)
        Log.d(TAG, "Notificação de vídeo salvo exibida")
    }

    private fun startRecording() {
        try {
            outputFilePath = createOutputFile()
            setupMediaRecorder(outputFilePath!!)
            openCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar gravação: ${e.message}", e)
            broadcastError("Erro ao iniciar gravação: ${e.message}")
            stopSelf()
        }
    }

    /**
     * Salva em /storage/emulated/0/Movies/BackgroundCam/ (pasta pública).
     * Visível em qualquer gerenciador de arquivos e na galeria após indexação.
     * O arquivo persiste mesmo após desinstalar o app.
     */
    private fun createOutputFile(): String {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "BackgroundCam"
        ).also { it.mkdirs() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(moviesDir, "VID_$timestamp.mp4")
        Log.d(TAG, "Arquivo de saída: ${file.absolutePath}")
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

        Log.d(TAG, "MediaRecorder configurado")
    }

    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("Câmera traseira não encontrada")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Câmera aberta com sucesso")
                cameraDevice = camera
                startCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Câmera desconectada")
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Erro na câmera: $error")
                camera.close(); cameraDevice = null
                broadcastError("Erro na câmera: código $error")
                stopSelf()
            }
        }, null)
    }

    private fun startCaptureSession() {
        val recorder = mediaRecorder ?: return
        val camera = cameraDevice ?: return
        val recorderSurface = recorder.surface

        camera.createCaptureSession(
            listOf(recorderSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Sessão configurada, iniciando gravação")
                    captureSession = session

                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
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
                    Log.d(TAG, "✅ Gravação iniciada!")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Falha ao configurar sessão")
                    broadcastError("Falha ao configurar sessão de câmera")
                    stopSelf()
                }
            },
            null
        )
    }

    /**
     * Para a gravação, indexa o arquivo no MediaStore para aparecer na galeria
     * e exibe uma notificação com atalho direto para o vídeo.
     */
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

            Log.d(TAG, "✅ Gravação salva em: $outputFilePath")

            outputFilePath?.let { indexVideoInGallery(it) }
                ?: broadcastStatus(isRecording = false)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar gravação: ${e.message}", e)
            broadcastError("Erro ao parar: ${e.message}")
        }
    }

    /**
     * Registra o vídeo no MediaStore do sistema.
     * Após isso o arquivo aparece em galeria, Google Fotos, etc.
     * O callback retorna um content:// URI estável para abrir o vídeo.
     */
    private fun indexVideoInGallery(filePath: String) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(filePath),
            arrayOf("video/mp4")
        ) { scannedPath, contentUri ->
            Log.d(TAG, "Vídeo indexado: $scannedPath | URI: $contentUri")

            // Informa a Activity: gravação parou, arquivo pronto para abrir
            broadcastStatus(
                isRecording = false,
                filePath = scannedPath ?: filePath,
                contentUri = contentUri?.toString()
            )

            // Notificação de conclusão — toque abre o vídeo no player
            showRecordingSavedNotification(scannedPath ?: filePath, contentUri)
        }
    }

    private fun broadcastStatus(
        isRecording: Boolean,
        filePath: String? = null,
        contentUri: String? = null
    ) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, isRecording)
            filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
            contentUri?.let { putExtra(EXTRA_CONTENT_URI, it) }
        })
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_RECORDING, false)
            putExtra(EXTRA_ERROR, message)
        })
    }
}
