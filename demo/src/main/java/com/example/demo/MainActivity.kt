package com.example.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.webzoplayer.WebzoPlayer

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
  private var player: WebzoPlayer? = null
  private var surfaceView: SurfaceView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // READ_EXTERNAL_STORAGEパーミッションが許可されているかチェックする
    val isGranted = ContextCompat.checkSelfPermission(
      this@MainActivity,
      Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    if (!isGranted) {
      requestPermissions(
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
        REQUEST_CODE_PERMISSION
      )
      return
    }
    setupSurface()

    val playButton = findViewById<Button>(R.id.button)
    playButton.setOnClickListener {
      val holder = surfaceView?.holder
      if (player == null && holder != null) {
        player = WebzoPlayer(holder.surface)
        player!!.src = "file:///storage/emulated/0/DCIM/Camera/PXL_20220609_085249872.mp4"
        player!!.play()
      } else if (holder != null) {
        player!!.play()
      }
    }
    val pauseButton = findViewById<Button>(R.id.button2)
    pauseButton.setOnClickListener {
      player?.pause()
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (requestCode != REQUEST_CODE_PERMISSION) return

    if (grantResults.isNotEmpty() && (grantResults.first() == PackageManager.PERMISSION_GRANTED)) {
      setupSurface()
    }
  }

  override fun surfaceCreated(holder: SurfaceHolder) {}

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    if (player != null) return
    player = WebzoPlayer(holder.surface)
    player!!.src = "file:///storage/emulated/0/DCIM/Camera/PXL_20220609_085249872.mp4"
    player!!.play()
  }

  override fun surfaceDestroyed(p0: SurfaceHolder) {
    player?.terminate()
  }

  private fun setupSurface() {
    surfaceView = findViewById(R.id.surfaceView)
    surfaceView?.holder?.addCallback(this)
  }

  companion object {
    const val REQUEST_CODE_PERMISSION = 1000
  }
}