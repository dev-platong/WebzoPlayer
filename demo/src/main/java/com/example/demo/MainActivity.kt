package com.example.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.webzoplayer.WebzoPlayer

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
  private var player: WebzoPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
    player!!.src = "file:///storage/emulated/0/DCIM/avc_stream_10seconds.mp4"
    player!!.play()
  }

  override fun surfaceDestroyed(p0: SurfaceHolder) {
    player?.terminate()
  }

  private fun setupSurface() {
    val surfaceView = SurfaceView(this)
    surfaceView.holder.addCallback(this)
    setContentView(surfaceView)
  }

  companion object {
    const val REQUEST_CODE_PERMISSION = 1000
  }
}