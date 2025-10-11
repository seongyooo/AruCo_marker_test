package com.example.test_exoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.test_exoplayer.databinding.ActivityMainBinding
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var socket: Socket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // 서버에서 받은 marker 및 grid 정보
    private var myMarkerId = -1
    private var gridRows = 1
    private var gridCols = 1
    private var myRow = 0
    private var myCol = 0
    private var myRotation = 0

    private val serverUrl = "http://192.168.0.141:5000" // ❗️ 실제 PC IP로 수정
    private val streamUrl = "udp://224.1.1.1:1234"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        setupSocketAndLayout()
        setupVideoCropLoop()
    }

    private fun setupSocketAndLayout() {
        socket = IO.socket(serverUrl)
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("Socket.IO", "서버 연결!")
            socket?.emit("client_ready")
            runOnUiThread { binding.tvStatus.text = "서버 연결됨, 대기 중..." }
        }
        socket?.on("server_command") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val payload = args[0] as JSONObject
                val cmd = payload.optString("command")
                when (cmd) {
                    "assign_id" -> {
                        myMarkerId = payload.getInt("marker_id")
                        runOnUiThread { binding.tvStatus.text = "내 마커ID: $myMarkerId" }
                    }
                    // =======================================================
                    // 여기가 수정된 부분입니다 (1): 'show_marker' 처리 로직 추가
                    // =======================================================
                    "show_marker" -> {
                        runOnUiThread { showMarkerUI() }
                    }
                    "start_playback" -> {
                        val layoutJson = JSONObject(payload.getString("data"))
                        setGridLayoutInfo(layoutJson)
                        runOnUiThread {
                            // 마커 UI를 숨기고, 비디오 타일 UI를 보이게 합니다.
                            binding.markerImageView.visibility = View.GONE
                            binding.tileImageView.visibility = View.VISIBLE
                            binding.tvStatus.text = "Grid수신: (${myRow},${myCol}) [$myRotation°]"
                        }
                    }
                }
            }
        }
        socket?.connect()
    }

    // 마커 이미지를 보여주는 함수 (새로 추가)
    private fun showMarkerUI() {
        binding.tileImageView.visibility = View.GONE
        binding.markerImageView.visibility = View.VISIBLE

        val resourceId = resources.getIdentifier("marker_$myMarkerId", "drawable", packageName)
        if (resourceId != 0) {
            binding.markerImageView.setImageResource(resourceId)
            binding.tvStatus.text = "마커 표시 중 (ID: #$myMarkerId)"
        } else {
            binding.tvStatus.text = "오류: 마커 이미지 없음 (ID: #$myMarkerId)"
        }
    }

    private fun setGridLayoutInfo(json: JSONObject) {
        val gridInfo = json.getJSONObject("grid_info")
        gridRows = gridInfo.getInt("rows")
        gridCols = gridInfo.getInt("cols")
        val layoutArray = json.getJSONArray("layout")
        for (i in 0 until layoutArray.length()) {
            val marker = layoutArray.getJSONObject(i)
            if (marker.getInt("id") == myMarkerId) {
                val gridPos = marker.getJSONObject("grid_pos")
                myRow = gridPos.getInt("row")
                myCol = gridPos.getInt("col")
                myRotation = marker.getInt("rotation")
                break
            }
        }
    }

    private fun setupVideoCropLoop() {
        binding.hiddenTextureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startPlayerWithSurface(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
    }

    private fun startPlayerWithSurface(surface: Surface) {
        player = ExoPlayer.Builder(this).build().apply {
            setVideoSurface(surface)
            setMediaItem(MediaItem.fromUri(streamUrl))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
        startCropLoop()
    }

    private var cropJob: Job? = null
    private fun startCropLoop() {
        cropJob?.cancel()
        cropJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val srcBitmap = binding.hiddenTextureView.bitmap
                if (srcBitmap == null) {
                    delay(20)
                    continue
                }

                if (gridCols > 0 && gridRows > 0) {
                    val tileW = srcBitmap.width / gridCols
                    val tileH = srcBitmap.height / gridRows
                    val left = myCol * tileW
                    val top = myRow * tileH

                    try {
                        val myTile = Bitmap.createBitmap(srcBitmap, left, top, tileW, tileH)
                        val rotated = rotateBitmap(myTile, myRotation)

                        withContext(Dispatchers.Main) {
                            binding.tileImageView.setImageBitmap(rotated)
                        }

                        // rotateBitmap이 새 비트맵을 만들었다면 원본 myTile은 재활용
                        if (rotated != myTile) {
                            myTile.recycle()
                        }
                        // 'rotated' 비트맵은 ImageView가 관리하므로 여기서 재활용하지 않음

                    } catch (e: Exception) {
                        Log.e("CropError", "비트맵 크롭/회전 중 오류: ${e.message}")
                    } finally {
                        // =======================================================
                        // 여기가 수정된 부분입니다 (2): srcBitmap.recycle() 삭제
                        // =======================================================
                        // srcBitmap은 TextureView가 관리하므로 절대 여기서 재활용하면 안 됩니다.
                        // srcBitmap.recycle()
                    }
                }
                delay(16)
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, angle: Int): Bitmap {
        if (angle == 0) return bitmap
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        // 마지막 인자 'true'는 필터링을 사용하여 더 부드러운 이미지를 만듭니다.
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return rotatedBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        cropJob?.cancel()
        player?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        socket?.disconnect()
    }
}