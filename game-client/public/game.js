const CONFIG = {
    CANVAS_WIDTH: 800,
    CANVAS_HEIGHT: 600,
    FPS: 60,
    PLAYER_SPEED: 5,
    BULLET_SPEED: 10,
    ENEMY_BULLET_SPEED: 4,
    SPAWN_RATE: 60,
    STAGE_BOSS_SCORE: 5000,
    MAX_STAGE: 5
};

const KEY_BITMASK = {
    UP: 1 << 0,
    DOWN: 1 << 1,
    LEFT: 1 << 2,
    RIGHT: 1 << 3,
    FIRE: 1 << 4
};

class Game {
    constructor() {
        this.canvas = document.getElementById('gameCanvas');
        this.ctx = this.canvas.getContext('2d');
        this.canvas.width = CONFIG.CANVAS_WIDTH;
        this.canvas.height = CONFIG.CANVAS_HEIGHT;
        
        this.state = 'menu';
        this.score = 0;
        this.lives = 3;
        this.stage = 1;
        this.enemiesKilled = 0;
        this.startTime = 0;
        this.gameTime = 0;
        this.frameCount = 0;
        this.lastFpsUpdate = 0;
        this.currentFps = 0;
        this.playerName = '';
        this.gameId = '';
        
        this.player = null;
        this.bullets = [];
        this.enemyBullets = [];
        this.enemies = [];
        this.explosions = [];
        this.stars = [];
        this.powerUps = [];
        
        this.inputState = 0;
        this.inputSequence = [];
        this.lastInputState = 0;
        this.frameWithoutInput = 0;
        
        this.spawnTimer = 0;
        this.bossActive = false;
        this.boss = null;
        
        this.keys = {};
        this.initStars();
        this.bindEvents();
        this.lastTime = 0;
    }
    
    initStars() {
        for (let i = 0; i < 100; i++) {
            this.stars.push({
                x: Math.random() * CONFIG.CANVAS_WIDTH,
                y: Math.random() * CONFIG.CANVAS_HEIGHT,
                speed: Math.random() * 2 + 0.5,
                size: Math.random() * 2 + 0.5
            });
        }
    }
    
    bindEvents() {
        document.addEventListener('keydown', (e) => this.handleKeyDown(e));
        document.addEventListener('keyup', (e) => this.handleKeyUp(e));
        
        document.getElementById('startBtn').addEventListener('click', () => this.startGame());
        document.getElementById('restartBtn').addEventListener('click', () => this.startGame());
        document.getElementById('uploadBtn').addEventListener('click', () => this.uploadScore());
    }
    
    handleKeyDown(e) {
        this.keys[e.code] = true;
        
        if (e.code === 'KeyP' && this.state === 'playing') {
            this.state = 'paused';
            document.getElementById('pause-overlay').classList.remove('hidden');
        } else if (e.code === 'KeyP' && this.state === 'paused') {
            this.state = 'playing';
            document.getElementById('pause-overlay').classList.add('hidden');
        }
        
        if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space', 
             'KeyW', 'KeyS', 'KeyA', 'KeyD'].includes(e.code)) {
            e.preventDefault();
        }
    }
    
    handleKeyUp(e) {
        this.keys[e.code] = false;
    }
    
    updateInputState() {
        let state = 0;
        if (this.keys['ArrowUp'] || this.keys['KeyW']) state |= KEY_BITMASK.UP;
        if (this.keys['ArrowDown'] || this.keys['KeyS']) state |= KEY_BITMASK.DOWN;
        if (this.keys['ArrowLeft'] || this.keys['KeyA']) state |= KEY_BITMASK.LEFT;
        if (this.keys['ArrowRight'] || this.keys['KeyD']) state |= KEY_BITMASK.RIGHT;
        if (this.keys['Space']) state |= KEY_BITMASK.FIRE;
        this.inputState = state;
    }
    
    recordInput() {
        if (this.inputState !== this.lastInputState || this.frameWithoutInput >= 255) {
            this.inputSequence.push({
                frame: this.frameCount,
                input: this.inputState
            });
            this.frameWithoutInput = 0;
            this.lastInputState = this.inputState;
        } else {
            this.frameWithoutInput++;
        }
    }
    
    startGame() {
        this.playerName = document.getElementById('playerName').value.trim() || 'Player1';
        this.gameId = this.generateGameId();
        
        this.state = 'playing';
        this.score = 0;
        this.lives = 3;
        this.stage = 1;
        this.enemiesKilled = 0;
        this.frameCount = 0;
        this.startTime = Date.now();
        this.gameTime = 0;
        
        this.player = new Player(CONFIG.CANVAS_WIDTH / 4, CONFIG.CANVAS_HEIGHT / 2);
        this.bullets = [];
        this.enemyBullets = [];
        this.enemies = [];
        this.explosions = [];
        this.powerUps = [];
        
        this.inputSequence = [];
        this.inputState = 0;
        this.lastInputState = 0;
        this.frameWithoutInput = 0;
        
        this.spawnTimer = 0;
        this.bossActive = false;
        this.boss = null;
        
        document.getElementById('start-screen').classList.add('hidden');
        document.getElementById('gameover-screen').classList.add('hidden');
        document.getElementById('game-screen').classList.remove('hidden');
        document.getElementById('pause-overlay').classList.add('hidden');
        document.getElementById('upload-status').textContent = '';
        document.getElementById('upload-status').className = 'upload-status';
        
        this.gameLoop(performance.now());
    }
    
    generateGameId() {
        return 'GAME-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
    }
    
    gameLoop(currentTime) {
        if (this.state === 'gameover') return;
        
        const deltaTime = (currentTime - this.lastTime) / 1000;
        this.lastTime = currentTime;
        
        if (this.state === 'playing') {
            this.update(deltaTime);
            this.recordInput();
            this.frameCount++;
        }
        
        this.render();
        this.updateFPS(currentTime);
        
        requestAnimationFrame((t) => this.gameLoop(t));
    }
    
    updateFPS(currentTime) {
        if (currentTime - this.lastFpsUpdate >= 1000) {
            this.currentFps = Math.round(this.frameCount * 1000 / (currentTime - this.lastFpsUpdate));
            document.getElementById('fps').textContent = this.currentFps;
            this.lastFpsUpdate = currentTime;
        }
    }
    
    update(deltaTime) {
        this.gameTime = (Date.now() - this.startTime) / 1000;
        
        this.updateInputState();
        this.updateStars();
        this.updatePlayer(deltaTime);
        this.updateBullets();
        this.updateEnemyBullets();
        this.updateEnemies(deltaTime);
        this.updateExplosions();
        this.updatePowerUps();
        this.spawnEnemies();
        this.checkCollisions();
        this.updateHUD();
        this.checkStageProgress();
    }
    
    updateStars() {
        for (let star of this.stars) {
            star.x -= star.speed;
            if (star.x < 0) {
                star.x = CONFIG.CANVAS_WIDTH;
                star.y = Math.random() * CONFIG.CANVAS_HEIGHT;
            }
        }
    }
    
    updatePlayer(deltaTime) {
        if (!this.player || !this.player.alive) return;
        
        let dx = 0, dy = 0;
        if (this.inputState & KEY_BITMASK.UP) dy -= CONFIG.PLAYER_SPEED;
        if (this.inputState & KEY_BITMASK.DOWN) dy += CONFIG.PLAYER_SPEED;
        if (this.inputState & KEY_BITMASK.LEFT) dx -= CONFIG.PLAYER_SPEED;
        if (this.inputState & KEY_BITMASK.RIGHT) dx += CONFIG.PLAYER_SPEED;
        
        this.player.x += dx;
        this.player.y += dy;
        
        this.player.x = Math.max(20, Math.min(CONFIG.CANVAS_WIDTH - 20, this.player.x));
        this.player.y = Math.max(20, Math.min(CONFIG.CANVAS_HEIGHT - 20, this.player.y));
        
        if (this.inputState & KEY_BITMASK.FIRE) {
            this.playerFire();
        }
        
        this.player.invincibleTimer = Math.max(0, this.player.invincibleTimer - deltaTime);
    }
    
    playerFire() {
        if (!this.player.canFire()) return;
        
        const bulletSpeed = CONFIG.BULLET_SPEED;
        const power = this.player.power;
        
        if (power === 1) {
            this.bullets.push(new Bullet(this.player.x + 25, this.player.y, bulletSpeed, 0));
        } else if (power === 2) {
            this.bullets.push(new Bullet(this.player.x + 25, this.player.y - 8, bulletSpeed, 0));
            this.bullets.push(new Bullet(this.player.x + 25, this.player.y + 8, bulletSpeed, 0));
        } else {
            this.bullets.push(new Bullet(this.player.x + 25, this.player.y, bulletSpeed, 0));
            this.bullets.push(new Bullet(this.player.x + 20, this.player.y - 10, bulletSpeed, -1));
            this.bullets.push(new Bullet(this.player.x + 20, this.player.y + 10, bulletSpeed, 1));
        }
        
        this.player.lastFire = Date.now();
    }
    
    updateBullets() {
        this.bullets = this.bullets.filter(bullet => {
            bullet.x += bullet.vx;
            bullet.y += bullet.vy;
            return bullet.x > 0 && bullet.x < CONFIG.CANVAS_WIDTH &&
                   bullet.y > 0 && bullet.y < CONFIG.CANVAS_HEIGHT;
        });
    }
    
    updateEnemyBullets() {
        this.enemyBullets = this.enemyBullets.filter(bullet => {
            bullet.x += bullet.vx;
            bullet.y += bullet.vy;
            return bullet.x > -10 && bullet.x < CONFIG.CANVAS_WIDTH + 10 &&
                   bullet.y > -10 && bullet.y < CONFIG.CANVAS_HEIGHT + 10;
        });
    }
    
    updateEnemies(deltaTime) {
        for (let enemy of this.enemies) {
            enemy.update(deltaTime, this.player, this);
        }
        
        this.enemies = this.enemies.filter(enemy => {
            if (enemy.x < -50 || enemy.health <= 0) {
                if (enemy.health <= 0) {
                    this.score += enemy.score;
                    this.enemiesKilled++;
                    this.explosions.push(new Explosion(enemy.x, enemy.y, enemy.isBoss ? 80 : 30));
                    
                    if (Math.random() < 0.15 && !enemy.isBoss) {
                        this.powerUps.push(new PowerUp(enemy.x, enemy.y));
                    }
                    
                    if (enemy.isBoss) {
                        this.bossActive = false;
                        this.boss = null;
                        this.stage++;
                        this.score += 2000 * (this.stage - 1);
                    }
                }
                return false;
            }
            return true;
        });
    }
    
    updateExplosions() {
        this.explosions = this.explosions.filter(exp => {
            exp.timer -= 0.05;
            return exp.timer > 0;
        });
    }
    
    updatePowerUps() {
        this.powerUps = this.powerUps.filter(p => {
            p.x -= 2;
            p.y += Math.sin(Date.now() / 200 + p.x) * 0.5;
            return p.x > -20;
        });
    }
    
    spawnEnemies() {
        if (this.bossActive) return;
        
        const nextBossScore = this.stage * CONFIG.STAGE_BOSS_SCORE;
        if (this.score >= nextBossScore && !this.bossActive) {
            this.spawnBoss();
            return;
        }
        
        this.spawnTimer++;
        const spawnRate = Math.max(20, CONFIG.SPAWN_RATE - this.stage * 8);
        
        if (this.spawnTimer >= spawnRate) {
            this.spawnTimer = 0;
            this.spawnEnemy();
        }
    }
    
    spawnEnemy() {
        const type = Math.random();
        const y = Math.random() * (CONFIG.CANVAS_HEIGHT - 100) + 50;
        const x = CONFIG.CANVAS_WIDTH + 30;
        
        if (type < 0.6) {
            this.enemies.push(new Enemy(x, y, 'normal', this.stage));
        } else if (type < 0.85) {
            this.enemies.push(new Enemy(x, y, 'fast', this.stage));
        } else {
            this.enemies.push(new Enemy(x, y, 'heavy', this.stage));
        }
    }
    
    spawnBoss() {
        this.bossActive = true;
        this.boss = new Enemy(CONFIG.CANVAS_WIDTH + 100, CONFIG.CANVAS_HEIGHT / 2, 'boss', this.stage);
        this.enemies.push(this.boss);
        
        for (let i = 0; i < 5; i++) {
            this.enemies.push(new Enemy(
                CONFIG.CANVAS_WIDTH + 50 + i * 30,
                CONFIG.CANVAS_HEIGHT / 2 - 80 + i * 40,
                'normal',
                this.stage
            ));
        }
    }
    
    checkCollisions() {
        if (!this.player || !this.player.alive) return;
        
        for (let i = this.bullets.length - 1; i >= 0; i--) {
            const bullet = this.bullets[i];
            for (let j = this.enemies.length - 1; j >= 0; j--) {
                const enemy = this.enemies[j];
                if (this.checkCircleCollision(bullet, enemy, 6, enemy.hitboxRadius)) {
                    enemy.health -= 1;
                    this.bullets.splice(i, 1);
                    this.explosions.push(new Explosion(bullet.x, bullet.y, 8));
                    break;
                }
            }
        }
        
        if (this.player.invincibleTimer <= 0) {
            for (let bullet of this.enemyBullets) {
                if (this.checkCircleCollision(bullet, this.player, 5, 15)) {
                    this.playerHit();
                    this.enemyBullets = this.enemyBullets.filter(b => b !== bullet);
                    break;
                }
            }
            
            for (let enemy of this.enemies) {
                if (this.checkCircleCollision(enemy, this.player, enemy.hitboxRadius, 15)) {
                    this.playerHit();
                    enemy.health -= 3;
                    break;
                }
            }
            
            for (let i = this.powerUps.length - 1; i >= 0; i--) {
                const p = this.powerUps[i];
                if (this.checkCircleCollision(p, this.player, 12, 15)) {
                    if (p.type === 'power') {
                        this.player.power = Math.min(3, this.player.power + 1);
                    } else if (p.type === 'life') {
                        this.lives = Math.min(5, this.lives + 1);
                    }
                    this.powerUps.splice(i, 1);
                    this.score += 100;
                }
            }
        }
    }
    
    checkCircleCollision(a, b, r1, r2) {
        const dx = a.x - b.x;
        const dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy) < r1 + r2;
    }
    
    playerHit() {
        this.lives--;
        this.player.invincibleTimer = 2;
        this.player.power = Math.max(1, this.player.power - 1);
        this.explosions.push(new Explosion(this.player.x, this.player.y, 40));
        
        if (this.lives <= 0) {
            this.gameOver();
        }
    }
    
    checkStageProgress() {
        if (this.stage > CONFIG.MAX_STAGE) {
            this.gameOver(true);
        }
    }
    
    gameOver(victory = false) {
        this.state = 'gameover';
        
        document.getElementById('game-screen').classList.add('hidden');
        document.getElementById('gameover-screen').classList.remove('hidden');
        document.getElementById('finalScore').textContent = this.score.toLocaleString();
        document.getElementById('enemiesKilled').textContent = this.enemiesKilled;
        document.getElementById('surviveTime').textContent = Math.floor(this.gameTime);
        document.getElementById('rank-info').textContent = victory ? '🎉 恭喜通关！' : '';
    }
    
    updateHUD() {
        document.getElementById('score').textContent = this.score.toLocaleString();
        document.getElementById('lives').textContent = this.lives;
        document.getElementById('stage').textContent = this.stage;
    }
    
    render() {
        const ctx = this.ctx;
        
        ctx.fillStyle = '#000011';
        ctx.fillRect(0, 0, CONFIG.CANVAS_WIDTH, CONFIG.CANVAS_HEIGHT);
        
        this.renderStars();
        
        if (this.state === 'playing' || this.state === 'paused') {
            this.renderPowerUps();
            this.renderEnemies();
            this.renderBullets();
            this.renderEnemyBullets();
            this.renderPlayer();
            this.renderExplosions();
            
            if (this.bossActive && this.boss) {
                this.renderBossHealthBar();
            }
        }
    }
    
    renderStars() {
        const ctx = this.ctx;
        for (let star of this.stars) {
            ctx.fillStyle = `rgba(255, 255, 255, ${0.3 + star.size / 3})`;
            ctx.fillRect(star.x, star.y, star.size, star.size);
        }
    }
    
    renderPlayer() {
        if (!this.player || !this.player.alive) return;
        
        const ctx = this.ctx;
        const p = this.player;
        
        if (p.invincibleTimer > 0 && Math.floor(p.invincibleTimer * 10) % 2 === 0) {
            return;
        }
        
        ctx.save();
        ctx.translate(p.x, p.y);
        
        ctx.fillStyle = '#4488ff';
        ctx.beginPath();
        ctx.moveTo(20, 0);
        ctx.lineTo(-15, -15);
        ctx.lineTo(-10, 0);
        ctx.lineTo(-15, 15);
        ctx.closePath();
        ctx.fill();
        
        ctx.fillStyle = '#66aaff';
        ctx.beginPath();
        ctx.moveTo(15, 0);
        ctx.lineTo(-5, -8);
        ctx.lineTo(-5, 8);
        ctx.closePath();
        ctx.fill();
        
        ctx.fillStyle = '#00ffff';
        ctx.beginPath();
        ctx.arc(0, 0, 4, 0, Math.PI * 2);
        ctx.fill();
        
        const flameLength = 10 + Math.random() * 8;
        const gradient = ctx.createLinearGradient(-15, 0, -15 - flameLength, 0);
        gradient.addColorStop(0, '#ffff00');
        gradient.addColorStop(0.5, '#ff6600');
        gradient.addColorStop(1, 'rgba(255, 0, 0, 0)');
        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.moveTo(-10, -5);
        ctx.lineTo(-15 - flameLength, 0);
        ctx.lineTo(-10, 5);
        ctx.closePath();
        ctx.fill();
        
        ctx.restore();
    }
    
    renderBullets() {
        const ctx = this.ctx;
        for (let bullet of this.bullets) {
            ctx.fillStyle = '#ffff00';
            ctx.shadowColor = '#ffff00';
            ctx.shadowBlur = 10;
            ctx.fillRect(bullet.x - 2, bullet.y - 2, 8, 4);
            ctx.shadowBlur = 0;
        }
    }
    
    renderEnemyBullets() {
        const ctx = this.ctx;
        for (let bullet of this.enemyBullets) {
            ctx.fillStyle = bullet.color || '#ff3366';
            ctx.shadowColor = bullet.color || '#ff3366';
            ctx.shadowBlur = 8;
            ctx.beginPath();
            ctx.arc(bullet.x, bullet.y, bullet.radius || 5, 0, Math.PI * 2);
            ctx.fill();
            ctx.shadowBlur = 0;
        }
    }
    
    renderEnemies() {
        const ctx = this.ctx;
        for (let enemy of this.enemies) {
            enemy.render(ctx);
        }
    }
    
    renderExplosions() {
        const ctx = this.ctx;
        for (let exp of this.explosions) {
            const progress = 1 - exp.timer / exp.maxTimer;
            const radius = exp.size * (0.5 + progress * 0.5);
            const alpha = 1 - progress;
            
            ctx.save();
            ctx.globalAlpha = alpha;
            
            ctx.strokeStyle = '#ffff00';
            ctx.lineWidth = 3;
            ctx.beginPath();
            ctx.arc(exp.x, exp.y, radius, 0, Math.PI * 2);
            ctx.stroke();
            
            ctx.fillStyle = '#ff6600';
            ctx.beginPath();
            ctx.arc(exp.x, exp.y, radius * 0.6, 0, Math.PI * 2);
            ctx.fill();
            
            ctx.fillStyle = '#ffffff';
            ctx.beginPath();
            ctx.arc(exp.x, exp.y, radius * 0.2, 0, Math.PI * 2);
            ctx.fill();
            
            ctx.restore();
        }
    }
    
    renderPowerUps() {
        const ctx = this.ctx;
        for (let p of this.powerUps) {
            ctx.save();
            ctx.translate(p.x, p.y);
            ctx.rotate(Date.now() / 500);
            
            const color = p.type === 'power' ? '#00ff00' : '#ff00ff';
            ctx.fillStyle = color;
            ctx.shadowColor = color;
            ctx.shadowBlur = 15;
            
            ctx.beginPath();
            for (let i = 0; i < 6; i++) {
                const angle = (i / 6) * Math.PI * 2;
                const x = Math.cos(angle) * 12;
                const y = Math.sin(angle) * 12;
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.closePath();
            ctx.fill();
            
            ctx.shadowBlur = 0;
            ctx.fillStyle = '#fff';
            ctx.font = 'bold 10px Arial';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.rotate(-Date.now() / 500);
            ctx.fillText(p.type === 'power' ? 'P' : 'L', 0, 0);
            
            ctx.restore();
        }
    }
    
    renderBossHealthBar() {
        const ctx = this.ctx;
        const barWidth = 600;
        const barHeight = 20;
        const x = (CONFIG.CANVAS_WIDTH - barWidth) / 2;
        const y = 10;
        
        ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
        ctx.fillRect(x - 2, y - 2, barWidth + 4, barHeight + 4);
        
        const healthPercent = this.boss.health / this.boss.maxHealth;
        const gradient = ctx.createLinearGradient(x, y, x + barWidth, y);
        gradient.addColorStop(0, '#ff0000');
        gradient.addColorStop(0.5, '#ff6600');
        gradient.addColorStop(1, '#ffff00');
        
        ctx.fillStyle = gradient;
        ctx.fillRect(x, y, barWidth * healthPercent, barHeight);
        
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 2;
        ctx.strokeRect(x, y, barWidth, barHeight);
        
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 14px Courier New';
        ctx.textAlign = 'center';
        ctx.fillText(`BOSS - STAGE ${this.stage}`, CONFIG.CANVAS_WIDTH / 2, y + 15);
    }
    
    async uploadScore() {
        const statusEl = document.getElementById('upload-status');
        statusEl.textContent = '正在上传...';
        statusEl.className = 'upload-status';
        
        try {
            const binaryData = InputSerializer.serialize({
                gameId: this.gameId,
                playerId: this.playerName,
                score: this.score,
                stage: this.stage,
                enemiesKilled: this.enemiesKilled,
                gameTime: this.gameTime,
                startTime: this.startTime,
                inputSequence: this.inputSequence,
                frameCount: this.frameCount
            });
            
            console.log(`Serialized data size: ${binaryData.byteLength} bytes`);
            
            const response = await fetch('http://localhost:8080/api/game/submit', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/octet-stream'
                },
                body: binaryData
            });
            
            const result = await response.json();
            
            if (result.success) {
                statusEl.textContent = `✓ 上传成功！当前排名: #${result.rank}`;
                statusEl.className = 'upload-status success';
            } else {
                statusEl.textContent = `✗ 上传失败: ${result.message}`;
                statusEl.className = 'upload-status error';
            }
        } catch (error) {
            console.error('Upload error:', error);
            statusEl.textContent = `✗ 网络错误: ${error.message}`;
            statusEl.className = 'upload-status error';
        }
    }
}

class Player {
    constructor(x, y) {
        this.x = x;
        this.y = y;
        this.alive = true;
        this.power = 1;
        this.lastFire = 0;
        this.fireRate = 120;
        this.invincibleTimer = 1;
    }
    
    canFire() {
        return Date.now() - this.lastFire >= this.fireRate;
    }
}

class Bullet {
    constructor(x, y, vx, vy) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
    }
}

class Enemy {
    constructor(x, y, type, stage) {
        this.x = x;
        this.y = y;
        this.type = type;
        this.stage = stage;
        this.fireTimer = Math.random() * 60;
        this.moveTimer = 0;
        this.movePattern = Math.floor(Math.random() * 3);
        
        if (type === 'normal') {
            this.health = 2 + Math.floor(stage / 2);
            this.maxHealth = this.health;
            this.speed = 1.5 + stage * 0.1;
            this.score = 100;
            this.hitboxRadius = 18;
            this.fireRate = 90 - stage * 5;
            this.color = '#ff4444';
        } else if (type === 'fast') {
            this.health = 1;
            this.maxHealth = 1;
            this.speed = 3 + stage * 0.2;
            this.score = 150;
            this.hitboxRadius = 12;
            this.fireRate = 120;
            this.color = '#44ff44';
        } else if (type === 'heavy') {
            this.health = 6 + stage;
            this.maxHealth = this.health;
            this.speed = 0.8;
            this.score = 300;
            this.hitboxRadius = 25;
            this.fireRate = 60;
            this.color = '#ff8800';
        } else if (type === 'boss') {
            this.health = 50 + stage * 30;
            this.maxHealth = this.health;
            this.speed = 1;
            this.score = 5000;
            this.hitboxRadius = 60;
            this.fireRate = 30;
            this.color = '#ff00ff';
            this.isBoss = true;
            this.phase = 0;
        }
    }
    
    update(deltaTime, player, game) {
        this.moveTimer++;
        this.fireTimer++;
        
        if (this.isBoss) {
            this.updateBoss(player, game);
            return;
        }
        
        this.x -= this.speed;
        
        if (this.movePattern === 0) {
            this.y += Math.sin(this.moveTimer / 30) * 1.5;
        } else if (this.movePattern === 1) {
            if (this.y < player.y) this.y += 0.8;
            else if (this.y > player.y) this.y -= 0.8;
        } else {
            this.y += Math.sin(this.moveTimer / 20) * 2;
        }
        
        this.y = Math.max(30, Math.min(CONFIG.CANVAS_HEIGHT - 30, this.y));
        
        if (this.fireTimer >= this.fireRate && this.x < CONFIG.CANVAS_WIDTH - 50) {
            this.fireTimer = 0;
            this.fire(player, game);
        }
    }
    
    updateBoss(player, game) {
        if (this.x > CONFIG.CANVAS_WIDTH - 150) {
            this.x -= 2;
            return;
        }
        
        this.y += Math.sin(this.moveTimer / 60) * 1.5;
        this.y = Math.max(100, Math.min(CONFIG.CANVAS_HEIGHT - 100, this.y));
        
        const healthPercent = this.health / this.maxHealth;
        if (healthPercent < 0.3) this.phase = 2;
        else if (healthPercent < 0.6) this.phase = 1;
        
        if (this.fireTimer >= this.fireRate - this.phase * 5) {
            this.fireTimer = 0;
            this.fireBoss(player, game);
        }
    }
    
    fire(player, game) {
        if (!player || !player.alive) return;
        
        const angle = Math.atan2(player.y - this.y, player.x - this.x);
        const speed = CONFIG.ENEMY_BULLET_SPEED;
        
        if (this.type === 'heavy') {
            for (let i = -1; i <= 1; i++) {
                const a = angle + i * 0.2;
                game.enemyBullets.push({
                    x: this.x - 15,
                    y: this.y,
                    vx: Math.cos(a) * speed,
                    vy: Math.sin(a) * speed,
                    radius: 6,
                    color: '#ff8800'
                });
            }
        } else {
            game.enemyBullets.push({
                x: this.x - 15,
                y: this.y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                radius: 5,
                color: this.color
            });
        }
    }
    
    fireBoss(player, game) {
        if (!player || !player.alive) return;
        
        const baseAngle = Math.atan2(player.y - this.y, player.x - this.x);
        const bulletCount = 8 + this.phase * 4;
        const spread = Math.PI / 3 + this.phase * Math.PI / 6;
        
        for (let i = 0; i < bulletCount; i++) {
            const angle = baseAngle - spread / 2 + (spread / (bulletCount - 1)) * i;
            const speed = CONFIG.ENEMY_BULLET_SPEED * 0.8;
            game.enemyBullets.push({
                x: this.x - 40,
                y: this.y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                radius: 8,
                color: '#ff00ff'
            });
        }
        
        if (this.phase >= 1 && this.fireTimer % 2 === 0) {
            for (let i = 0; i < 12; i++) {
                const angle = (i / 12) * Math.PI * 2 + this.moveTimer / 50;
                game.enemyBullets.push({
                    x: this.x,
                    y: this.y,
                    vx: Math.cos(angle) * 2.5,
                    vy: Math.sin(angle) * 2.5,
                    radius: 5,
                    color: '#ffff00'
                });
            }
        }
    }
    
    render(ctx) {
        ctx.save();
        ctx.translate(this.x, this.y);
        
        if (this.isBoss) {
            this.renderBoss(ctx);
        } else {
            this.renderNormal(ctx);
        }
        
        if (this.health < this.maxHealth && !this.isBoss) {
            const barWidth = 40;
            const barHeight = 4;
            ctx.fillStyle = '#333';
            ctx.fillRect(-barWidth / 2, -this.hitboxRadius - 10, barWidth, barHeight);
            ctx.fillStyle = '#00ff00';
            ctx.fillRect(-barWidth / 2, -this.hitboxRadius - 10, 
                        barWidth * (this.health / this.maxHealth), barHeight);
        }
        
        ctx.restore();
    }
    
    renderNormal(ctx) {
        const r = this.hitboxRadius;
        
        ctx.fillStyle = this.color;
        ctx.beginPath();
        ctx.moveTo(-r, 0);
        ctx.lineTo(r * 0.7, -r * 0.7);
        ctx.lineTo(r * 0.5, 0);
        ctx.lineTo(r * 0.7, r * 0.7);
        ctx.closePath();
        ctx.fill();
        
        ctx.fillStyle = '#ffff00';
        ctx.beginPath();
        ctx.arc(-r * 0.3, 0, r * 0.25, 0, Math.PI * 2);
        ctx.fill();
        
        if (this.type === 'heavy') {
            ctx.strokeStyle = '#ffaa00';
            ctx.lineWidth = 2;
            ctx.stroke();
        }
    }
    
    renderBoss(ctx) {
        const r = this.hitboxRadius;
        
        ctx.fillStyle = '#660066';
        ctx.beginPath();
        ctx.moveTo(-r, 0);
        ctx.lineTo(r * 0.5, -r);
        ctx.lineTo(r, -r * 0.3);
        ctx.lineTo(r, r * 0.3);
        ctx.lineTo(r * 0.5, r);
        ctx.closePath();
        ctx.fill();
        
        ctx.fillStyle = '#aa00aa';
        ctx.beginPath();
        ctx.moveTo(-r * 0.7, 0);
        ctx.lineTo(r * 0.3, -r * 0.6);
        ctx.lineTo(r * 0.5, 0);
        ctx.lineTo(r * 0.3, r * 0.6);
        ctx.closePath();
        ctx.fill();
        
        ctx.fillStyle = '#ff0000';
        ctx.shadowColor = '#ff0000';
        ctx.shadowBlur = 20;
        ctx.beginPath();
        ctx.arc(0, 0, r * 0.25, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
        
        ctx.fillStyle = '#000';
        ctx.beginPath();
        ctx.arc(0, 0, r * 0.12, 0, Math.PI * 2);
        ctx.fill();
        
        const coreSize = r * 0.3 + Math.sin(Date.now() / 100) * 5;
        ctx.strokeStyle = '#ff00ff';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(0, 0, coreSize, 0, Math.PI * 2);
        ctx.stroke();
    }
}

class Explosion {
    constructor(x, y, size) {
        this.x = x;
        this.y = y;
        this.size = size;
        this.timer = 1;
        this.maxTimer = 1;
    }
}

class PowerUp {
    constructor(x, y) {
        this.x = x;
        this.y = y;
        this.type = Math.random() < 0.7 ? 'power' : 'life';
    }
}

const game = new Game();
