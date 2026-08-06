/* Memoria — CodeHub by Wilson.E */
(function () {
  var EMOJIS = ['🚀', '⚡', '🎯', '🌈', '🔥', '🌙', '💎', '🎧'];
  var cards = [];
  var first = null, lock = false, moves = 0, pairs = 0;

  var gridEl = document.getElementById('grid');
  var movesEl = document.getElementById('moves');
  var pairsEl = document.getElementById('pairs');
  var statusEl = document.getElementById('status');

  function shuffle(arr) {
    for (var i = arr.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
    }
    return arr;
  }

  function newGame() {
    first = null;
    lock = false;
    moves = 0;
    pairs = 0;
    movesEl.textContent = '0';
    pairsEl.textContent = '0';
    statusEl.textContent = '';
    cards = shuffle(EMOJIS.concat(EMOJIS)).map(function (emoji, i) {
      return { emoji: emoji, id: i, matched: false };
    });
    render();
  }

  function render() {
    gridEl.innerHTML = '';
    cards.forEach(function (card) {
      var el = document.createElement('div');
      el.className = 'card';
      if (card.matched) el.classList.add('matched');
      if (card.flipped) el.classList.add('flipped');
      var back = document.createElement('div');
      back.className = 'card-face card-back';
      back.textContent = '?';
      var front = document.createElement('div');
      front.className = 'card-face card-front';
      front.textContent = card.emoji;
      el.appendChild(back);
      el.appendChild(front);
      el.addEventListener('click', function () {
        flip(card);
      });
      gridEl.appendChild(el);
    });
  }

  function flip(card) {
    if (lock || card.matched || card.flipped) return;
    card.flipped = true;
    if (!first) {
      first = card;
      render();
      return;
    }
    moves++;
    movesEl.textContent = moves;
    lock = true;
    render();
    setTimeout(function () {
      if (first.emoji === card.emoji) {
        first.matched = true;
        card.matched = true;
        pairs++;
        pairsEl.textContent = pairs;
        statusEl.textContent = '';
        if (pairs === EMOJIS.length) {
          statusEl.textContent = '🏆 ¡Ganaste en ' + moves + ' movimientos!';
          statusEl.className = 'status win';
        }
      } else {
        first.flipped = false;
        card.flipped = false;
      }
      first = null;
      lock = false;
      render();
    }, 600);
  }

  window.newGame = newGame;
  newGame();
})();
