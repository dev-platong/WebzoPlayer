package com.example.demo

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.webzoplayer.VideoDTO
import java.util.concurrent.TimeUnit

// FYI: Sorry I cannot work this yet.
class SearchMovie {
  private fun readMovie(applicationContext: Context) {
    val videoList = mutableListOf<VideoDTO>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
      MediaStore.Video.Media._ID,
      MediaStore.Video.Media.DISPLAY_NAME,
      MediaStore.Video.Media.DURATION,
      MediaStore.Video.Media.SIZE
    )
    val selection = "${MediaStore.Video.Media.DURATION} >= ?"
    val selectionArguments = arrayOf(TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS).toString())
    val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

    val contentResolver = applicationContext.contentResolver
    val query = contentResolver.query(
      collection,
      projection,
      selection,
      selectionArguments,
      sortOrder
    )
    query!!.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(projection[0])
      val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
      val durationColumn = cursor.getColumnIndexOrThrow(projection[2])
      val sizeColumn = cursor.getColumnIndexOrThrow(projection[3])

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idColumn)
        val name = cursor.getString(nameColumn)
        val duration = cursor.getInt(durationColumn)
        val size = cursor.getInt(sizeColumn)

        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        videoList += VideoDTO(contentUri, name, duration, size)
      }
    }
  }
}