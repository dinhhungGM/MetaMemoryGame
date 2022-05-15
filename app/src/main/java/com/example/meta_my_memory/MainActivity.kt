package com.example.meta_my_memory

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.jinatonic.confetti.CommonConfetti
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.example.meta_my_memory.models.*
import com.example.meta_my_memory.utils.EXTRA_BOARD_SIZE
import com.example.meta_my_memory.utils.EXTRA_GAME_NAME
import com.squareup.picasso.Picasso


class MainActivity : AppCompatActivity() {

  companion object {
    private const val TAG = "MainActivity"
    private const val CREATE_REQUEST_CODE = 248
  }

  private lateinit var clRoot: CoordinatorLayout
  private lateinit var rvBoard: RecyclerView
  private lateinit var tvNumMoves: TextView
  private lateinit var tvNumPairs: TextView

  private val db = Firebase.firestore
  private val firebaseAnalytics = Firebase.analytics
  private val remoteConfig = Firebase.remoteConfig
  private var gameName: String? = null
  private var customGameImages: List<String>? = null
  private lateinit var memoryGame: MemoryGame
  private lateinit var adapter: MemoryBoardAdapter
  private var boardSize = BoardSize.EASY

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    clRoot = findViewById(R.id.clRoot)
    rvBoard = findViewById(R.id.rvBoard)
    tvNumMoves = findViewById(R.id.tvNumMoves)
    tvNumPairs = findViewById(R.id.tvNumPairs)

    remoteConfig.setDefaultsAsync(mapOf("about_link" to "https://www.youtube.com/rpandey1234", "scaled_height" to 250L, "compress_quality" to 60L))
    remoteConfig.fetchAndActivate()
      .addOnCompleteListener(this) { task ->
        if (task.isSuccessful) {
          Log.i(TAG, "Fetch/activate succeeded, did config get updated? ${task.result}")
        } else {
          Log.w(TAG, "Remote config fetch failed")
        }
      }
    setupBoard()
  }

  override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.mi_refresh -> {
        if (memoryGame.getNumMoves() > 0 && !memoryGame.haveWonGame()) {
          showAlertDialog("Quit your current game?", null, View.OnClickListener {
            setupBoard()
          })
        } else {
          setupBoard()
        }
        return true
      }
      R.id.mi_new_size -> {
        showNewSizeDialog()
        return true
      }
      R.id.mi_custom -> {
        showCreationDialog()
        return true
      }
      R.id.mi_download -> {
        showDownloadDialog()
        return true
      }
      R.id.mi_about -> {
        firebaseAnalytics.logEvent("open_about_link", null)
        val aboutLink = remoteConfig.getString("about_link")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(aboutLink)))
        return true
      }
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == CREATE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
      val customGameName = data?.getStringExtra(EXTRA_GAME_NAME)
      if (customGameName == null) {
        Log.e(TAG, "Got null custom game from CreateActivity")
        return
      }
      downloadGame(customGameName)
    }
    super.onActivityResult(requestCode, resultCode, data)
  }

  @SuppressLint("InflateParams")
  private fun showDownloadDialog() {
    val boardDownloadView = LayoutInflater.from(this).inflate(R.layout.dialog_download_board, null)
    showAlertDialog("Fetch memory game", boardDownloadView, View.OnClickListener {
      val etDownloadGame = boardDownloadView.findViewById<EditText>(R.id.etDownloadGame)
      val gameToDownload = etDownloadGame.text.toString().trim()
      downloadGame(gameToDownload)
    })
  }

  private fun downloadGame(customGameName: String) {
    if (customGameName.isBlank()) {
      Snackbar.make(clRoot, "Game name can't be blank", Snackbar.LENGTH_LONG).show()
      Log.e(TAG, "Trying to retrieve an empty game name")
      return
    }
    firebaseAnalytics.logEvent("download_game_attempt") {
      param("game_name", customGameName)
    }
    db.collection("games").document(customGameName).get().addOnSuccessListener { document ->
      val userImageList = document.toObject(UserImageList::class.java)
      if (userImageList?.images == null)   {
        Log.e(TAG, "Invalid custom game data from Firebase")
        Snackbar.make(clRoot, "Sorry, we couldn't find any such game, '$customGameName'", Snackbar.LENGTH_LONG).show()
        return@addOnSuccessListener
      }
      firebaseAnalytics.logEvent("download_game_success") {
        param("game_name", customGameName)
      }
      val numCards = userImageList.images.size * 2
      boardSize = BoardSize.getByValue(numCards)
      customGameImages = userImageList.images
      gameName = customGameName
      // Pre-fetch the images for faster loading
      for (imageUrl in userImageList.images) {
        Picasso.get().load(imageUrl).fetch()
      }
      Snackbar.make(clRoot, "You're now playing '$customGameName'!", Snackbar.LENGTH_LONG).show()
      setupBoard()
    }.addOnFailureListener { exception ->
      Log.e(TAG, "Exception when retrieving game", exception)
    }
  }

  @SuppressLint("InflateParams")
  private fun showCreationDialog() {
    firebaseAnalytics.logEvent("creation_show_dialog", null)
    val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
    val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radioGroupSize)
    showAlertDialog("Create your own memory board", boardSizeView, View.OnClickListener {
      val desiredBoardSize = when (radioGroupSize.checkedRadioButtonId) {
        R.id.rbEasy -> BoardSize.EASY
        R.id.rbMedium -> BoardSize.MEDIUM
        else -> BoardSize.HARD
      }
      firebaseAnalytics.logEvent("creation_start_activity") {
        param("board_size", desiredBoardSize.name)
      }
      val intent = Intent(this, CreateActivity::class.java)
      intent.putExtra(EXTRA_BOARD_SIZE, desiredBoardSize)
      startActivityForResult(intent, CREATE_REQUEST_CODE)
    })
  }

  @SuppressLint("InflateParams")
  private fun showNewSizeDialog() {
    val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
    val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radioGroupSize)
    when (boardSize) {
      BoardSize.EASY -> radioGroupSize.check(R.id.rbEasy)
      BoardSize.MEDIUM -> radioGroupSize.check(R.id.rbMedium)
      BoardSize.HARD -> radioGroupSize.check(R.id.rbHard)
    }
    showAlertDialog("Choose new size", boardSizeView, View.OnClickListener {
      boardSize = when (radioGroupSize.checkedRadioButtonId) {
        R.id.rbEasy -> BoardSize.EASY
        R.id.rbMedium -> BoardSize.MEDIUM
        else -> BoardSize.HARD
      }
      gameName = null
      customGameImages = null
      setupBoard()
    })
  }

  private fun showAlertDialog(title: String, view: View?, positiveClickListener: View.OnClickListener) {
    AlertDialog.Builder(this)
      .setTitle(title)
      .setView(view)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("OK") { _, _ ->
        positiveClickListener.onClick(null)
      }.show()
  }

  private fun setupBoard() {

    db.collection("games").document(gameName ?: "[default]").get()
      .addOnSuccessListener { document ->
        val data = document.toObject(MemoryScore::class.java);

        if(data?.scores == null) {
          supportActionBar?.title = "Highest Score: 0";
        }else {
          val currentScore: Score? = data.scores!!.find { score -> score.board_size == boardSize.name };

          if(currentScore != null)
            supportActionBar?.title = "Highest Score: ${currentScore?.highest_score}";
          else
            supportActionBar?.title = "Highest Score: 0";
        }
      }

    memoryGame = MemoryGame(boardSize, customGameImages)
    when (boardSize) {
      BoardSize.EASY -> {
        tvNumMoves.text = "Easy: 4 x 2"
        tvNumPairs.text = "Pairs: 0/4"
      }
      BoardSize.MEDIUM -> {
        tvNumMoves.text = "Medium: 6 x 3"
        tvNumPairs.text = "Pairs: 0/9"
      }
      BoardSize.HARD -> {
        tvNumMoves.text = "Hard: 6 x 4"
        tvNumPairs.text = "Pairs: 0/12"
      }
    }
    tvNumPairs.setTextColor(ContextCompat.getColor(this, R.color.color_progress_none))
    adapter = MemoryBoardAdapter(this, boardSize, memoryGame.cards, object: MemoryBoardAdapter.CardClickListener {
      override fun onCardClicked(position: Int) {
        updateGameWithFlip(position)
      }
    })
    rvBoard.adapter = adapter
    rvBoard.setHasFixedSize(true)
    rvBoard.layoutManager = GridLayoutManager(this, boardSize.getWidth())
  }

  private fun saveHighestScore(gameName: String, highest_score: Int, board_size: String, scores: List<Score>, action: String) {
    if(action == "update") {
      db.collection("games").document(gameName)
        .update("scores", scores)
        .addOnCompleteListener(this) { task ->
          if (task.isSuccessful) {
            Log.i(TAG, "Save score succeeded,  ${task.result}")
            showAlertDialog("Your score is ${memoryGame.getNumMoves()} with difficult ${board_size}, Highest score: $highest_score", null, View.OnClickListener {

            })

          } else {

            Log.e(TAG, "Save score failed ${task.result}")
          }
        }
    }else {
      db.collection("games").document(gameName)
        .set(MemoryScore(scores))
        .addOnCompleteListener(this) { task ->
          if (task.isSuccessful) {
            Log.i(TAG, "Save score succeeded,  ${task.result}")
            showAlertDialog("Your score is ${memoryGame.getNumMoves()} with difficult ${board_size}, Highest score: $highest_score", null, View.OnClickListener {

            })

          } else {

            Log.e(TAG, "Save score failed ${task.result}")
          }
        }
    }
    supportActionBar?.title = "Highest Score: ${highest_score}";

  }

  // check win or not
  @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
  private fun updateGameWithFlip(position: Int) {
    // Error handling:
    if (memoryGame.haveWonGame()) {
      Snackbar.make(clRoot, "You already won! Use the menu to play again.", Snackbar.LENGTH_LONG).show()
      return
    }
    if (memoryGame.isCardFaceUp(position)) {
      Snackbar.make(clRoot, "Invalid move!", Snackbar.LENGTH_SHORT).show()
      return
    }

    // Actually flip the card
    if (memoryGame.flipCard(position)) {
      Log.i(TAG, "Found a match! Num pairs found: ${memoryGame.numPairsFound}")
      val color = ArgbEvaluator().evaluate(
        memoryGame.numPairsFound.toFloat() / boardSize.getNumPairs(),
        ContextCompat.getColor(this, R.color.color_progress_none),
        ContextCompat.getColor(this, R.color.color_progress_full)
      ) as Int
      tvNumPairs.setTextColor(color)
      tvNumPairs.text = "Pairs: ${memoryGame.numPairsFound} / ${boardSize.getNumPairs()}"
      if (memoryGame.haveWonGame()) {
        Snackbar.make(clRoot, "You won! Congratulations.", Snackbar.LENGTH_LONG).show()
        CommonConfetti.rainingConfetti(clRoot, intArrayOf(Color.YELLOW, Color.GREEN, Color.MAGENTA)).oneShot()

        db.collection("games").document(gameName ?: "[default]").get()
          .addOnSuccessListener{ document ->
           val data = document.toObject(MemoryScore::class.java);
            Log.i(TAG, "data,  $data")

            val currentScoreIndex: Int? = data?.scores?.indexOfFirst { score -> score.board_size == boardSize.name }


            val newScore = Score(
              boardSize.name, memoryGame.getNumMoves()
            )

            // Khong ton tai score trong array
            if(data?.scores == null || currentScoreIndex == null || currentScoreIndex == -1) {
              if(currentScoreIndex == -1) {
                val updatedScores =  data.scores!!.toMutableList();
                updatedScores.add(newScore);
                saveHighestScore(gameName ?: "[default]", memoryGame.getNumMoves(), boardSize.name,
                  updatedScores.toList(), "update"
                )
              }else {
                saveHighestScore(gameName ?: "[default]", memoryGame.getNumMoves(), boardSize.name,
                  listOf(newScore), "save"
                )
              }

            }else {
              val currentScore: Score = data.scores!![currentScoreIndex]
              if(memoryGame.getNumMoves() < currentScore.highest_score!!) {
                val newScore = Score(
                  boardSize.name, memoryGame.getNumMoves()
                );

                val updatedScores =  data.scores!!.toMutableList();
                updatedScores[currentScoreIndex] = newScore;

                saveHighestScore(gameName ?: "[default]", memoryGame.getNumMoves(), boardSize.name,
                  updatedScores.toList(), "update"
                );
              }else {
                  showAlertDialog("Your score is ${memoryGame.getNumMoves()} with difficult ${boardSize.name}, Highest score: ${currentScore.highest_score}", null, View.OnClickListener {
                })
              }
            }
          };



        firebaseAnalytics.logEvent("won_game") {
          param("game_name", gameName ?: "[default]")
          param("board_size", boardSize.name)
        }
      }
    }
    tvNumMoves.text = "Moves: ${memoryGame.getNumMoves()}"
    adapter.notifyDataSetChanged()
  }
}