/*
 * This file is part of Similar Image Finder (SIF).
 *
 * Copyright (C) 2025 otaltan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */


package com.example.similarimagefinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.util.Collections
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore


suspend fun searchSimilarImagesOptimized(
    imageUris: List<Uri>,
    context: Context,
): List<ImageSimilarity> = coroutineScope {
    val results = Collections.synchronizedList(mutableListOf<ImageSimilarity>())

    val hashes = imageUris.associateWith { uri ->
        coroutineContext.ensureActive()
        try {
            getBitmapFromUri(context, uri, scaleDown = true)?.let { bitmap ->
                ImagePHash.getHash(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    val semaphore = Semaphore(4)
    val jobs = mutableListOf<Job>()

    for (i in 0 until imageUris.size) {
        for (j in i + 1 until imageUris.size) {
            val uri1 = imageUris[i]
            val uri2 = imageUris[j]

            val job = launch(coroutineContext  + Dispatchers.Default) {
                coroutineContext.ensureActive()
                semaphore.acquire()
                try {
                    coroutineContext.ensureActive()

                    val hash1 = hashes[uri1]
                    val hash2 = hashes[uri2]

                    if (hash1 != null && hash2 != null) {
                        coroutineContext.ensureActive()
                        val score = ImagePHash.getSimilarityPercentage(hash1, hash2)
                        results.add(ImageSimilarity(uri1, uri2, score))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    semaphore.release()
                }
            }
            jobs.add(job)
        }
    }

    jobs.joinAll()
    return@coroutineScope results.toList()
}

fun getBitmapFromUri(context: Context, uri: Uri, scaleDown: Boolean = false): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            if (scaleDown) {
                inSampleSize = 4
            }
        }
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
