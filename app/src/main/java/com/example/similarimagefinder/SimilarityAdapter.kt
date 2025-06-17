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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.RecyclerView

class SimilarityAdapter(
    private val context: Context,
    private val items: List<ImageSimilarity>,
    private val activityLauncher: ActivityResultLauncher<Intent>
) : RecyclerView.Adapter<SimilarityAdapter.SimilarityViewHolder>() {

    inner class SimilarityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView1: ImageView = view.findViewById(R.id.imageView1)
        val imageView2: ImageView = view.findViewById(R.id.imageView2)
        val textViewIndex: TextView = view.findViewById(R.id.textViewIndex)
        val textViewSimilarity: TextView = view.findViewById(R.id.textViewSimilarity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimilarityViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_similar_image, parent, false)
        return SimilarityViewHolder(view)
    }

    override fun onBindViewHolder(holder: SimilarityViewHolder, position: Int) {
        val item = items[position]

        holder.imageView1.setImageBitmap(getThumbnail(context, item.uri1))
        holder.imageView2.setImageBitmap(getThumbnail(context, item.uri2))
        holder.textViewIndex.text = "#${position + 1}"
        holder.textViewSimilarity.text = "${item.score}%"

        holder.itemView.setOnClickListener {
            val intent = Intent(context, CompareActivity::class.java).apply {
                var uri1 = item.uri1.toString()
                var uri2 = item.uri2.toString()
                var folderUri = getParentFolderUri(item.uri1).toString()
                var score = item.score

                putExtra("IMAGE_URI_1", uri1)
                putExtra("IMAGE_URI_2", uri2)
                putExtra("FOLDER_URI", folderUri)
                putExtra("SCORE", score)
            }
            activityLauncher.launch(intent)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun getParentFolderUri(fileUri: Uri): Uri? {
        if (DocumentsContract.isDocumentUri(context, fileUri)) {
            val documentId = DocumentsContract.getDocumentId(fileUri)
            val path = documentId.substringAfter(":")
            val parentPath = path.substringBeforeLast("/")

            val treeDocId = "primary:$parentPath"
            return DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", treeDocId)
        }
        return null
    }

    private fun getThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                val scale = calculateInSampleSize(options, 80, 80)

                val options2 = BitmapFactory.Options().apply {
                    inSampleSize = scale
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    BitmapFactory.decodeStream(stream2, null, options2)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}