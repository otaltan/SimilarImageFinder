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
 *
 * Based on pHash.cpp by aetilius (https://github.com/aetilius/pHash),
 * licensed under GPL-3.0.
 */



package com.example.similarimagefinder

import android.graphics.Bitmap
import kotlin.math.*
import androidx.core.graphics.scale

object ImagePHash {

    private const val SIZE = 32
    private const val DCT_SIZE = 8

    fun getHash(bitmap: Bitmap): String {
        // 1. Convert bitmap to a non-hardware bitmap if necessary
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            bitmap
        }

        // 2. Scale down to a small size, e.g., 32x32 (pHash standard)
        val resizedBitmap = safeBitmap.scale(SIZE, SIZE)

        // 3. Convert to grayscale
        val grayValues = Array(SIZE) { DoubleArray(SIZE) }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Compute luminance value (convert to grayscale)
                grayValues[y][x] = 0.299 * r + 0.587 * g + 0.114 * b
            }
        }

        // 4. Compute the Discrete Cosine Transform (DCT)
        val dctValues = applyDCT(grayValues)

        // 5. Select DCT values (e.g., top-left 8x8 block, excluding the DC component)
        val dctLowFreq = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
        for (y in 0 until DCT_SIZE) {
            for (x in 0 until DCT_SIZE) {
                dctLowFreq[y][x] = dctValues[y][x]
            }
        }

        // 6. Calculate the median of the DCT values (excluding the DC component at [0][0])
        val list = mutableListOf<Double>()
        for (y in 0 until DCT_SIZE) {
            for (x in 0 until DCT_SIZE) {
                if (x != 0 || y != 0) {
                    list.add(dctLowFreq[y][x])
                }
            }
        }
        val median = list.sorted()[list.size / 2]

        // 7. Generate hash (1 if value > median, otherwise 0)
        val hash = StringBuilder()
        for (y in 0 until DCT_SIZE) {
            for (x in 0 until DCT_SIZE) {
                if (x != 0 || y != 0) {
                    hash.append(if (dctLowFreq[y][x] > median) "1" else "0")
                }
            }
        }
        return hash.toString()
    }


    fun hammingDistance(hash1: String, hash2: String): Int {
        return hash1.zip(hash2).count { it.first != it.second }
    }

    fun getSimilarityPercentage(hash1: String, hash2: String): Int {
        val distance = hammingDistance(hash1, hash2)
        return ((1.0 - distance / 63.0) * 100).roundToInt()
    }

    private fun applyDCT(f: Array<DoubleArray>): Array<DoubleArray> {
        val N = SIZE
        val F = Array(N) { DoubleArray(N) }

        for (u in 0 until N) {
            for (v in 0 until N) {
                var sum = 0.0
                for (i in 0 until N) {
                    for (j in 0 until N) {
                        sum += f[i][j] *
                                cos(((2 * i + 1) * u * Math.PI) / (2.0 * N)) *
                                cos(((2 * j + 1) * v * Math.PI) / (2.0 * N))
                    }
                }
                val cU = if (u == 0) 1 / sqrt(2.0) else 1.0
                val cV = if (v == 0) 1 / sqrt(2.0) else 1.0
                F[u][v] = 0.25 * cU * cV * sum
            }
        }
        return F
    }
}
