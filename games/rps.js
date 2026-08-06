/* Piedra Papel o Tijera — CodeHub by Wilson.E */
(function () {
  var NAMES = { rock: '🪨', paper: '📄', scissors: '✂️' };
  var RULES = { rock: 'scissors', paper: 'rock', scissors: 'paper' };
  var you = 0, cpu = 0;
  var playing = false;

  var emoteYou = document.getElementById('emote-you');
  var emoteCpu = document.getElementById('emote-cpu');
  var scoreYou = document.getElementById('score-you');
  var scoreCpu = document.getElementById('score-cpu');
  var resultEl = document.getElementById('result');
  var boxYou = document.getElementById('box-you');
  var boxCpu = document.getElementById('box-cpu');

  function play(pick) {
    if (playing) return;
    playing = true;
    boxYou.classList.add('playing');
    boxCpu.classList.add('playing');
    resultEl.textContent = '...';
    resultEl.className = 'result';

    var cpuPick = ['rock', 'paper', 'scissors'][Math.floor(Math.random() * 3)];

    setTimeout(function () {
      emoteYou.textContent = NAMES[pick];
      emoteCpu.textContent = NAMES[cpuPick];
      boxYou.classList.remove('playing');
      boxCpu.classList.remove('playing');

      var outcome;
      if (pick === cpuPick) {
        outcome = 'tie';
      } else if (RULES[pick] === cpuPick) {
        outcome = 'win';
        you++;
      } else {
        outcome = 'lose';
        cpu++;
      }
      scoreYou.textContent = you;
      scoreCpu.textContent = cpu;

      var msg = outcome === 'win' ? '🏆 ¡Ganaste!' : outcome === 'lose' ? '💻 Gana la CPU' : '🤝 Empate';
      resultEl.textContent = msg;
      resultEl.className = 'result ' + outcome;
      playing = false;
    }, 400);
  }

  function reset() {
    you = 0; cpu = 0;
    scoreYou.textContent = '0';
    scoreCpu.textContent = '0';
    emoteYou.textContent = '🤚';
    emoteCpu.textContent = '🤖';
    resultEl.textContent = '';
    resultEl.className = 'result';
  }

  window.play = play;
  window.reset = reset;
})();
