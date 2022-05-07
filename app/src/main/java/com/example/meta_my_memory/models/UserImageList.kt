package com.example.meta_my_memory.models

import com.google.firebase.firestore.PropertyName


data class UserImageList(@PropertyName("images") val images: List<String>? = null)