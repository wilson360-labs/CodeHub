<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Tetris - Wilson.E</title>
    <style>
        /* [Todo el CSS que te proporcioné antes, incluyendo estilos para game-container, game-board, etc.] */
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #0c0c0c 0%, #1a1a2e 25%, #16213e 50%, #0f3460 75%, #0c0c0c 100%);
            color: #ffffff;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            overflow: hidden;
        }

        .game-container {
            display: flex;
            gap: 2rem;
            padding: 2rem;
            background: rgba(0, 0, 0, 0.3);
            backdrop-filter: blur(20px);
            border-radius: 20px;
            border: 2px solid rgba(255, 69, 0, 0.3);
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
        }

        .game-board {
            position: relative;
        }

        #tetris-canvas {
            border: 3px solid #ff4500;
            border-radius: 10px;
            background: rgba(0, 0, 0, 0.8);
            box-shadow: 0 0 30px rgba(255, 69, 0, 0.3);
        }

        .game-info {
            min-width: 200px;
            display: flex;
            flex-direction: column;
            gap: 1.5rem;
        }

        .info-panel {
            background: linear-gradient(145deg, rgba(255, 255, 255, 0.1), rgba(255, 69, 0, 0.05));
            padding: 1.5rem;
            border-radius: 15px;
            border: 2px solid rgba(255, 69, 0, 0.2);
            text-align: center;
        }

        .info-panel h3 {
            color: #ff4500;
            margin-bottom: 1rem;
            font-size: 1.2rem;
        }

        .score, .level, .lines {
            font-size: 2rem;
            font-weight: bold;
            color: #ffbd69;
            text-shadow: 0 0 10px rgba(255, 189, 105, 0.5);
        }

        .next-piece {
            background: rgba(0, 0, 0, 0.6);
            width: 100px;
            height: 100px;
            margin: 1rem auto;
            border: 2px solid #ff4500;
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .controls {
            background: linear-gradient(145deg, rgba(255, 255, 255, 0.1), rgba(255, 69, 0, 0.05));
            padding: 1.5rem;
            border-radius: 15px;
            border: 2px solid rgba(255, 69, 0, 0.2);
        }

        .controls h3 {
            color: #ff4500;
            margin-bottom: 1rem;
            text-align: center;
        }

        .controls p {
            margin: 0.5rem 0;
            color: #ffbd69;
            font-size: 0.9rem;
        }

        .game-buttons {
            display: flex;
            flex-direction: column;
            gap: 1rem;
            margin-top: 1rem;
        }

        .btn {
            background: linear-gradient(45deg, #ff4500, #ff6b35);
            color: white;
            border: none;
            padding: 1rem 2rem;
            border-radius: 25px;
            font-size: 1rem;
            font-weight: bold;
            cursor: pointer;
            transition: all 0.3s ease;
            box-shadow: 0 5px 15px rgba(255, 69, 0, 0.3);
            user-select: none;
        }

        .btn:hover {
            transform: translateY(-3px);
            box-shadow: 0 8px 25px rgba(255, 69, 0, 0.5);
        }

        .btn:active {
            transform: translateY(0);
        }

        .game-over {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            background: rgba(0, 0, 0, 0.9);
            padding: 2rem;
            border-radius: 20px;
            border: 3px solid #ff4500;
            text-align: center;
            display: none;
            z-index: 100;
        }

        .game-over h2 {
            color: #ff4500;
            margin-bottom: 1rem;
            font-size: 2rem;
        }

        .game-over p {
            color: #ffbd69;
            margin-bottom: 1.5rem;
            font-size: 1.2rem;
        }

        .pulse {
            animation: pulse 2s infinite;
        }

        @keyframes pulse {
            0%, 100% {
                box-shadow: 0 0 30px rgba(255, 69, 0, 0.3);
            }
            50% {
                box-shadow: 0 0 50px rgba(255, 69, 0, 0.6);
            }
        }

        .mobile-controls {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            background: rgba(0, 0, 0, 0.8);
            backdrop-filter: blur(10px);
            padding: 1rem;
            display: none;
            z-index: 50;
        }

        .control-row {
            display: flex;
            justify-content: center;
            align-items: center;
            gap: 1rem;
            margin: 0.5rem 0;
        }

        .control-btn {
            background: linear-gradient(45deg, #ff4500, #ff6b35);
            color: white;
            border: none;
            padding: 1rem;
            border-radius: 15px;
            font-size: 1.2rem;
            font-weight: bold;
            min-width: 60px;
            min-height: 60px;
            cursor: pointer;
            transition: all 0.2s ease;
            box-shadow: 0 3px 10px rgba(255, 69, 0, 0.3);
            user-select: none;
            touch-action: manipulation;
        }

        .control-btn:active {
            transform: scale(0.95);
            box-shadow: 0 1px 5px rgba(255, 69, 0, 0.5);
        }

        .control-btn.wide {
            min-width: 120px;
        }

        .control-btn.rotate {
            border-radius: 50%;
            font-size: 1.5rem;
        }

        @media (max-width: 768px) {
            .game-container {
                flex-direction: column;
                padding: 1rem;
                margin-bottom: 180px;
            }
            
            .game-info {
                min-width: auto;
            }
            
            #tetris-canvas {
                width: 300px;
                height: 600px;
            }

            .mobile-controls {
                display: block;
            }

            .controls {
                display: none;
            }
        }

        @media (max-width: 480px) {
            .game-container {
                padding: 0.5rem;
                gap: 1rem;
            }

            #tetris-canvas {
                width: 250px;
                height: 500px;
            }

            .info-panel {
                padding: 1rem;
            }

            .score, .level, .lines {
                font-size: 1.5rem;
            }

            .control-btn {
                min-width: 50px;
                min-height: 50px;
                font-size: 1rem;
            }

            .control-btn.wide {
                min-width: 100px;
            }
        }
    </style>
</head>
<body>
    <div class="game-container">
        <div class="game-board">
            <canvas id="tetris-canvas" width="400" height="800"></canvas>
            <div class="game-over" id="game-over">
                <h2>¡Game Over!</h2>
                <p>Puntuación Final: <span id="final-score">0</span></p>
                <button class="btn" onclick="restartGame()">Jugar de Nuevo</button>
            </div>
        </div>
        
        <div class="game-info">
            <div class="info-panel">
                <h3>Puntuación</h3>
                <div class="score" id="score">0</div>
            </div>
            
            <div class="info-panel">
                <h3>Nivel</h3>
                <div class="level" id="level">1</div>
            </div>
            
            <div class="info-panel">
                <h3>Líneas</h3>
                <div class="lines" id="lines">0</div>
            </div>
            
            <div class="info-panel">
                <h3>Siguiente</h3>
                <canvas class="next-piece" id="next-canvas" width="100" height="100"></canvas>
            </div>
            
            <div class="controls">
                <h3>Controles</h3>
                <p>← → Mover</p>
                <p>↓ Bajar rápido</p>
                <p>ESPACIO Rotar</p>
                <p>P Pausa</p>
            </div>
            
            <div class="game-buttons">
                <button class="btn" onclick="togglePause()">Pausa</button>
                <button class="btn" onclick="restartGame()">Reiniciar</button>
            </div>
        </div>
    </div>

    <div class="mobile-controls">
        <div class="control-row">
            <button class="control-btn" onclick="togglePause()">⏸️</button>
            <button class="control-btn rotate" onclick="rotatePiece()">🔄</button>
        </div>
        <div class="control-row">
            <button class="control-btn" ontouchstart="startMoveLeft()" ontouchend="stopMove()">⬅️</button>
            <button class="control-btn wide" ontouchstart="startFastDrop()" ontouchend="stopFastDrop()">⬇️</button>
            <button class="control-btn" ontouchstart="startMoveRight()" ontouchend="stopMove()">➡️</button>
        </div>
    </div>

    <script>
        const canvas = document.getElementById('tetris-canvas');
        const ctx = canvas.getContext('2d');
        const nextCanvas = document.getElementById('next-canvas');
        const nextCtx = nextCanvas.getContext('2d');

        const BOARD_WIDTH = 10;
        const BOARD_HEIGHT = 20;
        let BLOCK_SIZE = canvas.width / BOARD_WIDTH;

        let board = Array(BOARD_HEIGHT).fill().map(() => Array(BOARD_WIDTH).fill(0));
        let currentPiece = null;
        let nextPiece = null;
        let score = 0;
        let level = 1;
        let lines = 0;
        let gameRunning = false;
        let isPaused = false;
        let dropTime = 0;
        let dropInterval = 1000;

        let moveInterval = null;
        let fastDropInterval = null;

        const PIECES = [
            { shape: [[1, 1, 1, 1]], color: '#00ffff' },
            { shape: [[1, 1], [1, 1]], color: '#ffff00' },
            { shape: [[0, 1, 0], [1, 1, 1]], color: '#800080' },
            { shape: [[0, 1, 1], [1, 1, 0]], color: '#00ff00' },
            { shape: [[1, 1, 0], [0, 1, 1]], color: '#ff0000' },
            { shape: [[1, 0, 0], [1, 1, 1]], color: '#0000ff' },
            { shape: [[0, 0, 1], [1, 1, 1]], color: '#ffa500' }
        ];

        class Piece {
            constructor(shape, color) {
                this.shape = shape;
                this.color = color;
                this.x = Math.floor(BOARD_WIDTH / 2) - Math.floor(shape[0].length / 2);
                this.y = 0;
            }

            draw(context, offsetX = 0, offsetY = 0, blockSize = BLOCK_SIZE) {
                context.fillStyle = this.color;
                context.strokeStyle = '#ffffff';
                context.lineWidth = 2;
                
                for (let y = 0; y < this.shape.length; y++) {
                    for (let x = 0; x < this.shape[y].length; x++) {
                        if (this.shape[y][x]) {
                            const drawX = (this.x + x + offsetX) * blockSize;
                            const drawY = (this.y + y + offsetY) * blockSize;
                            
                            context.fillRect(drawX, drawY, blockSize, blockSize);
                            context.strokeRect(drawX, drawY, blockSize, blockSize);
                        }
                    }
                }
            }

            rotate() {
                const rotated = this.shape[0].map((_, i) =>
                    this.shape.map(row => row[i]).reverse()
                );
                
                const originalShape = this.shape;
                this.shape = rotated;
                
                if (this.isColliding()) {
                    this.shape = originalShape;
                }
            }

            move(dx, dy) {
                this.x += dx;
                this.y += dy;
                
                if (this.isColliding()) {
                    this.x -= dx;
                    this.y -= dy;
                    return false;
                }
                return true;
            }

            isColliding() {
                for (let y = 0; y < this.shape.length; y++) {
                    for (let x = 0; x < this.shape[y].length; x++) {
                        if (this.shape[y][x]) {
                            const newX = this.x + x;
                            const newY = this.y + y;
                            
                            if (newX < 0 || newX >= BOARD_WIDTH || 
                                newY >= BOARD_HEIGHT || 
                                (newY >= 0 && board[newY][newX])) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        }

        function getRandomPiece() {
            const pieceData = PIECES[Math.floor(Math.random() * PIECES.length)];
            return new Piece(pieceData.shape, pieceData.color);
        }

        function drawBoard() {
            ctx.fillStyle = '#000000';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            
            for (let y = 0; y < BOARD_HEIGHT; y++) {
                for (let x = 0; x < BOARD_WIDTH; x++) {
                    if (board[y][x]) {
                        ctx.fillStyle = board[y][x];
                        ctx.fillRect(x * BLOCK_SIZE, y * BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE);
                        ctx.strokeStyle = '#ffffff';
                        ctx.lineWidth = 2;
                        ctx.strokeRect(x * BLOCK_SIZE, y * BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE);
                    }
                }
            }
            
            ctx.strokeStyle = 'rgba(255, 69, 0, 0.2)';
            ctx.lineWidth = 1;
            for (let x = 0; x <= BOARD_WIDTH; x++) {
                ctx.beginPath();
                ctx.moveTo(x * BLOCK_SIZE, 0);
                ctx.lineTo(x * BLOCK_SIZE, canvas.height);
                ctx.stroke();
            }
            for (let y = 0; y <= BOARD_HEIGHT; y++) {
                ctx.beginPath();
                ctx.moveTo(0, y * BLOCK_SIZE);
                ctx.lineTo(canvas.width, y * BLOCK_SIZE);
                ctx.stroke();
            }
        }

        function drawNextPiece() {
            nextCtx.fillStyle = '#000000';
            nextCtx.fillRect(0, 0, nextCanvas.width, nextCanvas.height);
            
            if (nextPiece) {
                const blockSize = 20;
                const offsetX = (nextCanvas.width - nextPiece.shape[0].length * blockSize) / 2 / blockSize;
                const offsetY = (nextCanvas.height - nextPiece.shape.length * blockSize) / 2 / blockSize;
                
                nextCtx.fillStyle = nextPiece.color;
                nextCtx.strokeStyle = '#ffffff';
                nextCtx.lineWidth = 1;
                
                for (let y = 0; y < nextPiece.shape.length; y++) {
                    for (let x = 0; x < nextPiece.shape[y].length; x++) {
                        if (nextPiece.shape[y][x]) {
                            const drawX = (offsetX + x) * blockSize;
                            const drawY = (offsetY + y) * blockSize;
                            
                            nextCtx.fillRect(drawX, drawY, blockSize, blockSize);
                            nextCtx.strokeRect(drawX, drawY, blockSize, blockSize);
                        }
                    }
                }
            }
        }

        function placePiece() {
            for (let y = 0; y < currentPiece.shape.length; y++) {
                for (let x = 0; x < currentPiece.shape[y].length; x++) {
                    if (currentPiece.shape[y][x]) {
                        const boardX = currentPiece.x + x;
                        const boardY = currentPiece.y + y;
                        
                        if (boardY >= 0) {
                            board[boardY][boardX] = currentPiece.color;
                        }
                    }
                }
            }
            
            clearLines();
            spawnNewPiece();
        }

        function clearLines() {
            let linesCleared = 0;
            
            for (let y = BOARD_HEIGHT - 1; y >= 0; y--) {
                if (board[y].every(cell => cell !== 0)) {
                    board.splice(y, 1);
                    board.unshift(Array(BOARD_WIDTH).fill(0));
                    linesCleared++;
                    y++;
                }
            }
            
            if (linesCleared > 0) {
                lines += linesCleared;
                score += linesCleared * 100 * level;
                level = Math.floor(lines / 10) + 1;
                dropInterval = Math.max(50, 1000 - (level - 1) * 50);
                
                updateDisplay();
            }
        }

        function spawnNewPiece() {
            currentPiece = nextPiece || getRandomPiece();
            nextPiece = getRandomPiece();
            
            if (currentPiece.isColliding()) {
                gameOver();
            }
        }

        function gameOver() {
            gameRunning = false;
            document.getElementById('final-score').textContent = score;
            document.getElementById('game-over').style.display = 'block';
            document.getElementById('game-over').classList.add('pulse');
        }

        function updateDisplay() {
            document.getElementById('score').textContent = score;
            document.getElementById('level').textContent = level;
            document.getElementById('lines').textContent = lines;
        }

        function togglePause() {
            if (gameRunning) {
                isPaused = !isPaused;
                if (isPaused) {
                    ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
                    ctx.fillRect(0, 0, canvas.width, canvas.height);
                    ctx.fillStyle = '#ff4500';
                    ctx.font = '48px Segoe UI';
                    ctx.textAlign = 'center';
                    ctx.fillText('PAUSED', canvas.width / 2, canvas.height / 2);
                }
            }
        }

        function restartGame() {
            board = Array(BOARD_HEIGHT).fill().map(() => Array(BOARD_WIDTH).fill(0));
            score = 0;
            level = 1;
            lines = 0;
            dropTime = 0;
            dropInterval = 1000;
            gameRunning = true;
            isPaused = false;
            
            currentPiece = null;
            nextPiece = null;
            spawnNewPiece();
            
            document.getElementById('game-over').style.display = 'none';
            document.getElementById('game-over').classList.remove('pulse');
            updateDisplay();
        }

        function startMoveLeft() {
            moveInterval = setInterval(() => {
                if (currentPiece && gameRunning && !isPaused) {
                    currentPiece.move(-1, 0);
                }
            }, 100);
        }

        function startMoveRight() {
            moveInterval = setInterval(() => {
                if (currentPiece && gameRunning && !isPaused) {
                    currentPiece.move(1, 0);
                }
            }, 100);
        }

        function startFastDrop() {
            fastDropInterval = setInterval(() => {
                if (currentPiece && gameRunning && !isPaused) {
                    currentPiece.move(0, 1);
                }
            }, 50);
        }

        function stopMove() {
            clearInterval(moveInterval);
        }

        function stopFastDrop() {
            clearInterval(fastDropInterval);
        }

        function rotatePiece() {
            if (currentPiece && gameRunning && !isPaused) {
                currentPiece.rotate();
            }
        }

        document.addEventListener('keydown', (event) => {
            if (!gameRunning || isPaused) return;

            switch (event.key) {
                case 'ArrowLeft':
                    currentPiece.move(-1, 0);
                    break;
                case 'ArrowRight':
                    currentPiece.move(1, 0);
                    break;
                case 'ArrowDown':
                    currentPiece.move(0, 1);
                    break;
                case ' ':
                    currentPiece.rotate();
                    break;
                case 'p':
                case 'P':
                    togglePause();
                    break;
            }
        });

        function resizeCanvas() {
            canvas.width = Math.min(window.innerWidth * 0.8, 400);
            canvas.height = canvas.width * 2;
            nextCanvas.width = Math.min(canvas.width / 4, 100);
            nextCanvas.height = nextCanvas.width;
            BLOCK_SIZE = canvas.width / BOARD_WIDTH;
        }

        window.addEventListener('resize', resizeCanvas);
        resizeCanvas();

        function gameLoop(timestamp) {
            if (gameRunning && !isPaused) {
                if (timestamp - dropTime > dropInterval) {
                    if (currentPiece && !currentPiece.move(0, 1)) {
                        placePiece();
                    }
                    dropTime = timestamp;
                }
                
                drawBoard();
                if (currentPiece) {
                    currentPiece.draw(ctx);
                }
                drawNextPiece();
            }
            
            requestAnimationFrame(gameLoop);
        }

        restartGame();
        requestAnimationFrame(gameLoop);
    </script>
</body>
</html>