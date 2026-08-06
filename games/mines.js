/* Buscaminas — CodeHub by Wilson.E */
(function () {
  var ROWS = 9, COLS = 9, MINES = 10;
  var board, revealed, flags, gameOver, started, seconds, timerId;

  var boardEl = document.getElementById('board');
  var mineEl = document.getElementById('mine-count');
  var timeEl = document.getElementById('timer');
  var statusEl = document.getElementById('status');

  function init() {
    board = [];
    revealed = [];
    flags = [];
    for (var r = 0; r < ROWS; r++) {
      board[r] = [];
      revealed[r] = [];
      flags[r] = [];
      for (var c = 0; c < COLS; c++) {
        board[r][c] = 0;
        revealed[r][c] = false;
        flags[r][c] = false;
      }
    }
    gameOver = false;
    started = false;
    seconds = 0;
    if (timerId) clearInterval(timerId);
    timerId = null;
    mineEl.textContent = String(MINES).padStart(3, '0');
    timeEl.textContent = '000';
    statusEl.textContent = '';
    render();
  }

  function placeMines(safeR, safeC) {
    var placed = 0;
    while (placed < MINES) {
      var r = Math.floor(Math.random() * ROWS);
      var c = Math.floor(Math.random() * COLS);
      if ((r === safeR && c === safeC) || board[r][c] === -1) continue;
      board[r][c] = -1;
      placed++;
    }
    for (var r = 0; r < ROWS; r++) {
      for (var c = 0; c < COLS; c++) {
        if (board[r][c] === -1) continue;
        board[r][c] = 0;
        for (var dr = -1; dr <= 1; dr++) {
          for (var dc = -1; dc <= 1; dc++) {
            var nr = r + dr, nc = c + dc;
            if (nr >= 0 && nr < ROWS && nc >= 0 && nc < COLS && board[nr][nc] === -1) {
              board[r][c]++;
            }
          }
        }
      }
    }
  }

  function render() {
    boardEl.innerHTML = '';
    boardEl.style.gridTemplateColumns = 'repeat(' + COLS + ', 34px)';
    for (var r = 0; r < ROWS; r++) {
      for (var c = 0; c < COLS; c++) {
        (function (r, c) {
          var cell = document.createElement('div');
          cell.className = 'cell';
          if (revealed[r][c]) {
            cell.classList.add('open');
            if (board[r][c] === -1) {
              cell.textContent = '💣';
              cell.classList.add('mine');
            } else if (board[r][c] > 0) {
              cell.textContent = board[r][c];
              cell.classList.add('n' + board[r][c]);
            }
          } else if (flags[r][c]) {
            cell.textContent = '🚩';
            cell.classList.add('flag');
          }
          cell.addEventListener('click', function () {
            if (gameOver) return;
            if (flags[r][c]) return;
            if (!started) {
              placeMines(r, c);
              started = true;
              startTimer();
            }
            reveal(r, c);
          });
          cell.addEventListener('contextmenu', function (e) {
            e.preventDefault();
            if (gameOver || revealed[r][c]) return;
            flags[r][c] = !flags[r][c];
            updateMineCount();
            render();
            checkWin();
          });
          cell.addEventListener('touchstart', function (e) {
            if (e.target.closest('.cell')) {
              if (e.target.dataset.qmark === '1') return;
              e.target.dataset.qmark = '1';
              setTimeout(function () { delete e.target.dataset.qmark; }, 700);
            }
          });
          boardEl.appendChild(cell);
        })(r, c);
      }
    }
  }

  function reveal(r, c) {
    if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return;
    if (revealed[r][c] || flags[r][c]) return;
    revealed[r][c] = true;
    if (board[r][c] === -1) {
      endGame(false);
      return;
    }
    if (board[r][c] === 0) {
      for (var dr = -1; dr <= 1; dr++) {
        for (var dc = -1; dc <= 1; dc++) {
          reveal(r + dr, c + dc);
        }
      }
    }
    render();
    checkWin();
  }

  function updateMineCount() {
    var count = 0;
    for (var r = 0; r < ROWS; r++) {
      for (var c = 0; c < COLS; c++) {
        if (flags[r][c]) count++;
      }
    }
    mineEl.textContent = String(MINES - count).padStart(3, '0');
  }

  function checkWin() {
    var opened = 0;
    for (var r = 0; r < ROWS; r++) {
      for (var c = 0; c < COLS; c++) {
        if (revealed[r][c]) opened++;
      }
    }
    if (!gameOver && opened === ROWS * COLS - MINES) endGame(true);
  }

  function endGame(win) {
    gameOver = true;
    if (timerId) clearInterval(timerId);
    if (win) {
      statusEl.textContent = '🏆 ¡Ganaste!';
      statusEl.className = 'win';
    } else {
      statusEl.textContent = '💥 ¡Boom!';
      statusEl.className = 'lose';
      for (var r = 0; r < ROWS; r++) {
        for (var c = 0; c < COLS; c++) {
          if (board[r][c] === -1) revealed[r][c] = true;
        }
      }
      render();
    }
  }

  function startTimer() {
    timerId = setInterval(function () {
      seconds++;
      timeEl.textContent = String(seconds).padStart(3, '0');
    }, 1000);
  }

  window.newGame = init;
  init();
})();
