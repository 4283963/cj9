const InputSerializer = (function() {
    const MAGIC_NUMBER = 0x5253;
    const VERSION = 1;
    const HEADER_SIZE = 4;

    function serialize(data) {
        const gameIdBytes = encodeUTF8(data.gameId);
        const playerIdBytes = encodeUTF8(data.playerId);
        
        const inputCount = data.inputSequence.length;
        const inputDataSize = inputCount * 5;
        
        const totalSize = HEADER_SIZE + 
                         1 + gameIdBytes.length + 
                         1 + playerIdBytes.length +
                         4 + 2 + 4 + 4 + 8 + 4 +
                         4 + inputDataSize;
        
        const buffer = new ArrayBuffer(totalSize);
        const view = new DataView(buffer);
        let offset = 0;
        
        view.setUint16(offset, MAGIC_NUMBER, false);
        offset += 2;
        view.setUint8(offset, VERSION);
        offset += 1;
        view.setUint8(offset, 0);
        offset += 1;
        
        view.setUint8(offset, gameIdBytes.length);
        offset += 1;
        for (let i = 0; i < gameIdBytes.length; i++) {
            view.setUint8(offset + i, gameIdBytes[i]);
        }
        offset += gameIdBytes.length;
        
        view.setUint8(offset, playerIdBytes.length);
        offset += 1;
        for (let i = 0; i < playerIdBytes.length; i++) {
            view.setUint8(offset + i, playerIdBytes[i]);
        }
        offset += playerIdBytes.length;
        
        view.setInt32(offset, data.score, false);
        offset += 4;
        view.setUint16(offset, data.stage, false);
        offset += 2;
        view.setUint32(offset, data.enemiesKilled, false);
        offset += 4;
        view.setFloat32(offset, data.gameTime, false);
        offset += 4;
        view.setBigUint64(offset, BigInt(data.startTime), false);
        offset += 8;
        view.setUint32(offset, data.frameCount, false);
        offset += 4;
        
        view.setUint32(offset, inputCount, false);
        offset += 4;
        
        for (let i = 0; i < inputCount; i++) {
            const input = data.inputSequence[i];
            view.setUint32(offset, input.frame, false);
            offset += 4;
            view.setUint8(offset, input.input);
            offset += 1;
        }
        
        return buffer;
    }

    function deserialize(buffer) {
        const view = new DataView(buffer);
        let offset = 0;
        
        const magic = view.getUint16(offset, false);
        offset += 2;
        if (magic !== MAGIC_NUMBER) {
            throw new Error('Invalid magic number: ' + magic);
        }
        
        const version = view.getUint8(offset);
        offset += 1;
        if (version !== VERSION) {
            throw new Error('Unsupported version: ' + version);
        }
        
        offset += 1;
        
        const gameIdLen = view.getUint8(offset);
        offset += 1;
        const gameId = decodeUTF8(new Uint8Array(buffer, offset, gameIdLen));
        offset += gameIdLen;
        
        const playerIdLen = view.getUint8(offset);
        offset += 1;
        const playerId = decodeUTF8(new Uint8Array(buffer, offset, playerIdLen));
        offset += playerIdLen;
        
        const score = view.getInt32(offset, false);
        offset += 4;
        const stage = view.getUint16(offset, false);
        offset += 2;
        const enemiesKilled = view.getUint32(offset, false);
        offset += 4;
        const gameTime = view.getFloat32(offset, false);
        offset += 4;
        const startTime = Number(view.getBigUint64(offset, false));
        offset += 8;
        const frameCount = view.getUint32(offset, false);
        offset += 4;
        
        const inputCount = view.getUint32(offset, false);
        offset += 4;
        
        const inputSequence = [];
        for (let i = 0; i < inputCount; i++) {
            const frame = view.getUint32(offset, false);
            offset += 4;
            const input = view.getUint8(offset);
            offset += 1;
            inputSequence.push({ frame, input });
        }
        
        return {
            gameId,
            playerId,
            score,
            stage,
            enemiesKilled,
            gameTime,
            startTime,
            frameCount,
            inputSequence
        };
    }

    function encodeUTF8(str) {
        const bytes = [];
        for (let i = 0; i < str.length; i++) {
            let code = str.charCodeAt(i);
            if (code < 0x80) {
                bytes.push(code);
            } else if (code < 0x800) {
                bytes.push(0xc0 | (code >> 6));
                bytes.push(0x80 | (code & 0x3f));
            } else if (code < 0xd800 || code >= 0xe000) {
                bytes.push(0xe0 | (code >> 12));
                bytes.push(0x80 | ((code >> 6) & 0x3f));
                bytes.push(0x80 | (code & 0x3f));
            } else {
                i++;
                code = 0x10000 + (((code & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
                bytes.push(0xf0 | (code >> 18));
                bytes.push(0x80 | ((code >> 12) & 0x3f));
                bytes.push(0x80 | ((code >> 6) & 0x3f));
                bytes.push(0x80 | (code & 0x3f));
            }
        }
        return new Uint8Array(bytes);
    }

    function decodeUTF8(bytes) {
        let str = '';
        let i = 0;
        while (i < bytes.length) {
            let byte = bytes[i++];
            if (byte < 0x80) {
                str += String.fromCharCode(byte);
            } else if (byte < 0xe0) {
                str += String.fromCharCode(((byte & 0x1f) << 6) | (bytes[i++] & 0x3f));
            } else if (byte < 0xf0) {
                str += String.fromCharCode(
                    ((byte & 0x0f) << 12) | 
                    ((bytes[i++] & 0x3f) << 6) | 
                    (bytes[i++] & 0x3f)
                );
            } else {
                let code = ((byte & 0x07) << 18) |
                           ((bytes[i++] & 0x3f) << 12) |
                           ((bytes[i++] & 0x3f) << 6) |
                           (bytes[i++] & 0x3f);
                code -= 0x10000;
                str += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff));
            }
        }
        return str;
    }

    function toHex(buffer) {
        const bytes = new Uint8Array(buffer);
        let hex = '';
        for (let i = 0; i < bytes.length; i++) {
            hex += bytes[i].toString(16).padStart(2, '0') + ' ';
        }
        return hex.trim();
    }

    function calculateChecksum(buffer) {
        const bytes = new Uint8Array(buffer);
        let checksum = 0;
        for (let i = 0; i < bytes.length; i++) {
            checksum = (checksum + bytes[i]) % 256;
        }
        return checksum;
    }

    return {
        serialize,
        deserialize,
        toHex,
        calculateChecksum,
        MAGIC_NUMBER,
        VERSION
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = InputSerializer;
}
