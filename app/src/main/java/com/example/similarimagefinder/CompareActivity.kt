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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import androidx.core.net.toUri

class CompareActivity : AppCompatActivity() {

    private lateinit var imageViewTop: ImageView
    private lateinit var imageViewBottom: ImageView
    private lateinit var slider: SeekBar
    private lateinit var tvThresholdValueComp: TextView
    private lateinit var checkbox1: CheckBox
    private lateinit var checkbox2: CheckBox
    private lateinit var btnDelete: Button
    private lateinit var filenameText1: TextView
    private lateinit var filenameText2: TextView

    private var uri1: Uri? = null
    private var uri2: Uri? = null
    private var folderUri: Uri? = null
    private var isTopImageVisible = true
    private var movedImgValue = 0
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        initViews()
        initToolbar()
        loadIntentData()
        setupImageViews()
        initSlider()
        initCheckBoxes()
        setupDeleteButton()
    }

    private fun initViews() {
        imageViewTop = findViewById(R.id.imageViewTop)
        imageViewBottom = findViewById(R.id.imageViewBottom)
        slider = findViewById(R.id.slider)
        tvThresholdValueComp = findViewById(R.id.tvThresholdValueComp)
        checkbox1 = findViewById(R.id.checkboxImage1)
        checkbox2 = findViewById(R.id.checkboxImage2)
        btnDelete = findViewById(R.id.btnDeleteSelected)
        filenameText1 = findViewById(R.id.filenameImage1)
        filenameText2 = findViewById(R.id.filenameImage2)
    }

    private fun initToolbar() {
        val topAppBar: MaterialToolbar = findViewById(R.id.topAppBar)
        topAppBar.title = "Compare Images"
        topAppBar.setNavigationOnClickListener { finish() }
    }

    private fun loadIntentData() {
        uri1 = intent.getStringExtra("IMAGE_URI_1")?.toUri()
        uri2 = intent.getStringExtra("IMAGE_URI_2")?.toUri()
        folderUri = intent.getStringExtra("FOLDER_URI")?.toUri()

        val score = intent.getIntExtra("SCORE", 0)
        tvThresholdValueComp.text = "$score%"

        if (uri1 == null || uri2 == null || folderUri == null) {
            Toast.makeText(this, "Failed to load images.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            filenameText1.text = getFileNameFromUri(uri1!!)
            filenameText2.text = getFileNameFromUri(uri2!!)
        }
    }

    private fun setupImageViews() {
        imageViewTop.setImageURI(uri2)
        imageViewBottom.setImageURI(uri1)

        imageViewTop.setOnClickListener {
            isTopImageVisible = !isTopImageVisible
            imageViewTop.alpha = if (isTopImageVisible) 1f else 0f
            slider.progress = if (isTopImageVisible) 100 else 0
        }
    }

    private fun initSlider() {
        slider.max = 100
        slider.progress = 100
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                imageViewTop.alpha = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initCheckBoxes() {
        val updateButtonLabel = {
            btnDelete.text = if (!checkbox1.isChecked && !checkbox2.isChecked) {
                "Keep images"
            } else {
                "Move to delete"
            }
        }

        checkbox1.setOnCheckedChangeListener { _, _ -> updateButtonLabel() }
        checkbox2.setOnCheckedChangeListener { _, _ -> updateButtonLabel() }
    }

    private fun setupDeleteButton() {
        btnDelete.setOnClickListener {
            val selectedImages = mutableListOf<Uri>()
            uri1?.let { if (checkbox1.isChecked) selectedImages.add(it) }
            uri2?.let { if (checkbox2.isChecked) selectedImages.add(it) }

            movedImgValue = when {
                checkbox1.isChecked && !checkbox2.isChecked -> 1
                !checkbox1.isChecked && checkbox2.isChecked -> 2
                checkbox1.isChecked && checkbox2.isChecked -> 3
                else -> 0
            }

            if (selectedImages.isEmpty()) {
                finishWithResult()
            } else {
                moveToDelete(selectedImages)
            }
        }
    }

    private fun moveToDelete(uris: List<Uri>) {
        scope.launch {
            val resolver = contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(folderUri!!)
            val folderDocumentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)

            var deleteUri: Uri? = withContext(Dispatchers.IO) {
                resolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "delete") {
                            val documentId = cursor.getString(0)
                            return@use DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)
                        }
                    }
                    null
                }
            }

            if (deleteUri == null) {
                deleteUri = DocumentsContract.createDocument(
                    resolver,
                    folderDocumentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    "delete"
                )
            }

            if (deleteUri == null) {
                Toast.makeText(this@CompareActivity, "Unable to create delete folder.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            for (sourceUri in uris) {
                try {
                    val name = getFileNameFromUri(sourceUri)
                    val mimeType = resolver.getType(sourceUri) ?: "image/jpeg"

                    val destUri = DocumentsContract.createDocument(resolver, deleteUri, mimeType, name)
                    if (destUri != null) {
                        resolver.openInputStream(sourceUri)?.use { inStream ->
                            resolver.openOutputStream(destUri)?.use { outStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        try {
                            DocumentsContract.deleteDocument(resolver, sourceUri)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this@CompareActivity, "Failed to delete original: $name", Toast.LENGTH_SHORT).show()
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@CompareActivity, "Failed to move image(s)", Toast.LENGTH_SHORT).show()
                }
            }

            Toast.makeText(this@CompareActivity, "Image(s) successfully moved to delete folder.", Toast.LENGTH_SHORT).show()
            finishWithResult()
        }
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra("IMAGE_URI_1", uri1.toString())
            putExtra("IMAGE_URI_2", uri2.toString())
            putExtra("REFRESH_NEEDED", true)
            putExtra("MOVED_IMG", movedImgValue)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "image.jpg"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

}
