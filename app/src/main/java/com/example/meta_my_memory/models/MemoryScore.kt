package com.example.meta_my_memory.models

import com.google.firebase.firestore.PropertyName

data class MemoryScore(@PropertyName("scores") var scores: List<Score>? = null)
