package com.example.webzoplayer

// FROM: https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement
interface HTMLMediaElement {
  var src: String
  /*
   * Pauses the media playback.
   */
  fun pause()
  fun play()
}