package com.autotv.player.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Vídeos guardados no celular (Download, Filmes, Câmera etc.), lidos da galeria do Android. */
object LocalVideos {
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** Lista os vídeos, do mais novo para o mais antigo. BLOQUEANTE (chame em Dispatchers.IO). */
    fun list(context: Context): List<Item> {
        if (!hasPermission(context)) {
            error("Abra o AutoTV no celular, aba Baixados, e permita o acesso aos vídeos.")
        }
        val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val out = ArrayList<Item>()
        context.contentResolver.query(base, cols, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(base, c.getLong(idCol)).toString()
                val name = (c.getString(nameCol) ?: "Vídeo").substringBeforeLast('.')
                val folder = c.getString(bucketCol)
                out += streamItem(name, uri, folder, uri).copy(durationSec = c.getLong(durCol) / 1000)
            }
        }
        return out
    }
}
