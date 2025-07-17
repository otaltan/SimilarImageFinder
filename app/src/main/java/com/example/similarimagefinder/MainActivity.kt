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

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import androidx.core.net.toUri
import com.google.android.material.slider.Slider
import kotlin.compareTo

class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_POST_NOTIFICATIONS = 100
    private val REQUEST_CODE_OPEN_DIRECTORY = 1001
    private var selectedFolderUri: Uri? = null
    private var similarityMinValue: Float = 80.0F
    private var resultList: List<ImageSimilarity> = emptyList()
    private var allResults: List<ImageSimilarity> = emptyList()
    private var searchJob: Job? = null
    private var isSearching = false
    private var imageNumber = 0

    private lateinit var imageCount: TextView
    private lateinit var btnOpenFolder: Button
    private lateinit var fabSearch: FloatingActionButton
    private lateinit var progressIndicator: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var sliderThreshold: Slider
    private lateinit var textResults: TextView
    private lateinit var textSelectedFolder: TextView
    private lateinit var tvThresholdValue: TextView
    private lateinit var compareActivityLauncher: ActivityResultLauncher<Intent>
    private lateinit var adapter: SimilarityAdapter

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val CHANNEL_ID = "search_status_channel"
    private val NOTIFICATION_ID = 1
    private val SEARCH_NOTIFICATION_ID = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkAndRequestNotificationPermission()

        btnOpenFolder = findViewById(R.id.btnOpenFolder)
        fabSearch = findViewById(R.id.btnSearch)
        progressIndicator = findViewById(R.id.progressIndicator)
        recyclerView = findViewById(R.id.recyclerViewSimilarImages)
        sliderThreshold = findViewById(R.id.sliderThreshold)
        textResults = findViewById(R.id.textResults)
        textSelectedFolder = findViewById(R.id.textSelectedFolder)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        imageCount = findViewById(R.id.imageCount)

        sliderThreshold.value = similarityMinValue
        btnOpenFolder.text = "Choose folder"
        progressIndicator.visibility = View.GONE
        tvThresholdValue.text = "$similarityMinValue%"

        val topAppBar: MaterialToolbar = findViewById(R.id.topAppBar)
        topAppBar.title = "Similar Image Finder"
        topAppBar.setNavigationOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://github.com/oaltan".toUri()
            }
            startActivity(intent)
        }

        compareActivityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val refreshNeeded = data?.getBooleanExtra("REFRESH_NEEDED", false) ?: false

                if (refreshNeeded && selectedFolderUri != null) {
                    val uriStr1 = data.getStringExtra("IMAGE_URI_1")
                    val uriStr2 = data.getStringExtra("IMAGE_URI_2")
                    val movedImgValue = data.getIntExtra("MOVED_IMG", 0)

                    if (uriStr1 != null && uriStr2 != null) {
                        val uri1 = uriStr1.toUri()
                        val uri2 = uriStr2.toUri()
                        removeEntries(uri1, uri2, movedImgValue)
                    }
                }
            }
        }

        btnOpenFolder.setOnClickListener {
            if (isSearching) {
                searchJob?.cancel()
            } else {
                allResults = emptyList()
                openDirectoryPicker()
            }
        }

        sliderThreshold.addOnChangeListener { slider, value, _ ->
            similarityMinValue = value
            tvThresholdValue.text = "${similarityMinValue.toInt()}%"
        }

        fabSearch.setOnClickListener {
            if (selectedFolderUri == null) {
                Toast.makeText(this, "Please select a folder first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (imageNumber<2) {
                Toast.makeText(this, "Select a folder that contains at least two images.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (allResults.isNotEmpty()) {
                updateDisplayedResults()
                return@setOnClickListener
            }

            isSearching = true
            fabSearch.isEnabled = false
            fabSearch.visibility = View.GONE
            progressIndicator.visibility = View.VISIBLE

            btnOpenFolder.apply {
                text = "Cancel Search"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.red))
            }

            recyclerView.adapter = SimilarityAdapter(this@MainActivity, emptyList<ImageSimilarity>(), compareActivityLauncher)

            fabSearch.post {
                val imageUris = getImagesFromFolder(selectedFolderUri!!)
                startImageSearch(imageUris)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            val treeUri = data?.data ?: return

            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val pickedDir = DocumentFile.fromTreeUri(this, treeUri)

            if (pickedDir != null && pickedDir.isDirectory) {
                selectedFolderUri = treeUri
                val folderName = getFolderDisplayName(selectedFolderUri!!)
                textSelectedFolder.text = "Selected folder: $folderName"
                imageNumber = countImages(selectedFolderUri!!)
                imageCount.text = "$imageNumber Images"
            } else {
                Toast.makeText(this, "Invalid folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    private fun getFolderDisplayName(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        return if (parts.size == 2) parts[1] else docId
    }

    private fun getImagesFromFolder(folderUri: Uri): List<Uri> {
        val imageUris = mutableListOf<Uri>()
        val pickedDir = DocumentFile.fromTreeUri(this, folderUri)
        if (pickedDir != null && pickedDir.isDirectory) {
            for (file in pickedDir.listFiles()) {
                if (file.isFile && isImageFile(file.name ?: "")) {
                    imageUris.add(file.uri)
                }
            }
        }
        return imageUris
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun countImages(folderUri: Uri): Int {
        val folder = DocumentFile.fromTreeUri(this@MainActivity, folderUri) ?: return 0
        imageNumber = 0

        for (file in folder.listFiles()) {
            if (file.isFile && file.type?.startsWith("image/") == true) {
                imageNumber++
            }
        }
        return imageNumber
    }

    private fun updateDisplayedResults() {
        val filtered = allResults.filter { it.score >= similarityMinValue }
        resultList = filtered
        adapter = SimilarityAdapter(this, resultList, compareActivityLauncher)
        recyclerView.adapter = adapter

        if (resultList.isEmpty()) {
            textResults.text = "No similar images found"
            textResults.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_result))
            recyclerView.visibility = View.GONE
        } else {
            val count = resultList.size
            textResults.text = "Found $count image pair${if (count == 1) "" else "s"}"
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun removeEntries(uri1: Uri, uri2: Uri, movedImgValue: Int) {
        if (movedImgValue == 1){
            resultList = resultList.filterNot {
                it.uri1 == uri1 || it.uri2 == uri1
            }
            allResults = allResults.filterNot {
                it.uri1 == uri1 || it.uri2 == uri1
            }
        }
        else if (movedImgValue == 2){
            resultList = resultList.filterNot {
                it.uri1 == uri2 || it.uri2 == uri2
            }
            allResults = allResults.filterNot {
                it.uri1 == uri2 || it.uri2 == uri2
            }
        }
        else if (movedImgValue == 3) {
            resultList = resultList.filterNot {
                (it.uri1 == uri1 && it.uri2 == uri2) || (it.uri1 == uri2 && it.uri2 == uri1)
            }
            resultList = resultList.filterNot {
                it.uri1 == uri1 || it.uri2 == uri1
            }
            resultList = resultList.filterNot {
                it.uri1 == uri2 || it.uri2 == uri2
            }

            allResults = allResults.filterNot {
                (it.uri1 == uri1 && it.uri2 == uri2) || (it.uri1 == uri2 && it.uri2 == uri1)
            }
            allResults = allResults.filterNot {
                it.uri1 == uri1 || it.uri2 == uri1
            }
            allResults = allResults.filterNot {
                it.uri1 == uri2 || it.uri2 == uri2
            }
        }
        else if (movedImgValue == 0) {
            resultList = resultList.filterNot {
                (it.uri1 == uri1 && it.uri2 == uri2) || (it.uri1 == uri2 && it.uri2 == uri1)
            }
            allResults = allResults.filterNot {
                (it.uri1 == uri1 && it.uri2 == uri2) || (it.uri1 == uri2 && it.uri2 == uri1)
            }
        }

        adapter = SimilarityAdapter(this, resultList, compareActivityLauncher)
        recyclerView.adapter = adapter

        if (resultList.isEmpty()) {
            textResults.text = "No images left to compare"
            textResults.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_result))
        } else {
            val count = resultList.size
            textResults.text = "$count image pair${if (count == 1) "" else "s"} left"
            textResults.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_result))
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun startImageSearch(imageUris: List<Uri>) {
        searchJob?.cancel()

        showSearchInProgressNotification()

        searchJob = mainScope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    searchSimilarImagesOptimized(imageUris, this@MainActivity)
                }.sortedByDescending{it.score}

                allResults = results
                val filteredResults = results.filter { it.score >= similarityMinValue }
                resultList = filteredResults

                if (filteredResults.isEmpty()) {
                    textResults.text = "No similar images found"
                    textResults.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_result))
                } else {
                    val count = resultList.size
                    textResults.text = "Found $count image pair${if (count == 1) "" else "s"}"
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = SimilarityAdapter(this@MainActivity, filteredResults, compareActivityLauncher)
                }
            } catch (e: CancellationException) {
                Toast.makeText(this@MainActivity, "Search canceled", Toast.LENGTH_SHORT).show()
            } finally {
                progressIndicator.visibility = View.GONE
                fabSearch.visibility = View.VISIBLE
                isSearching = false
                fabSearch.isEnabled = true
                btnOpenFolder.apply {
                    text = "Choose folder"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sif_secondary))
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.button_background))
                }

                NotificationManagerCompat.from(this@MainActivity).cancel(SEARCH_NOTIFICATION_ID)
                showSearchCompleteNotification()

                cancelSearchingNotification()

                val notification = NotificationCompat.Builder(this@MainActivity, CHANNEL_ID)
                    .setContentTitle("Similar Image Finder")
                    .setContentText("Searching complete!")
                    .setSmallIcon(R.drawable.ic_done)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()

                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID + 1, notification)
            }
        }
    }

    private fun showSearchInProgressNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Similar Image Finder")
            .setContentText("Searching for similar images…")
            .setSmallIcon(R.drawable.baseline_image_search_24)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (!hasNotificationPermission()) {
            checkAndRequestNotificationPermission()
            return
        }
        NotificationManagerCompat.from(this@MainActivity).notify(SEARCH_NOTIFICATION_ID, notification)
    }

    private fun showSearchCompleteNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Similar Image Finder")
            .setContentText("Search complete!")
            .setSmallIcon(R.drawable.ic_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (!hasNotificationPermission()) {
            checkAndRequestNotificationPermission()
            return
        }
        NotificationManagerCompat.from(this@MainActivity).notify(SEARCH_NOTIFICATION_ID, notification)
    }

    private fun cancelSearchingNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

}
