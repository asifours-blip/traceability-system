class LocalStorageService {
    setItem(key, value) {
        try {
            let serializedValue;
            if ((typeof value === 'object' || Array.isArray(value)) && value !== null) {
                // 对象（非数组、非函数）进行序列化
                serializedValue = JSON.stringify(value);
            } else {
                // 其他类型直接存储
                serializedValue = value;
            }
            localStorage.setItem(key, serializedValue);
        } catch (error) {
            console.error('存储数据到 localStorage 失败:', error);
        }
    }
    getItem(key) {
        try {
            const serializedValue = localStorage.getItem(key);
            if (serializedValue === null) {
                return null;
            }
            try {
                // 尝试反序列化
                return JSON.parse(serializedValue);
            } catch (parseError) {
                // 如果反序列化失败，说明存储的是非对象类型
                return serializedValue;
            }
        } catch (error) {
            console.error('从 localStorage 获取数据失败:', error);
            return null;
        }
    }

    removeItem(key) {
        localStorage.removeItem(key);
    }
    clear() {
        localStorage.clear();
    }
}

class DateTimeUtils {
    formatTimestamp(timestamp, format = 'YYYY-MM-DD HH:mm:ss') {
        const time = parseInt(timestamp)
        const date = new Date(time);
        const year = date.getFullYear();
        const month = this.padZero(date.getMonth() + 1);
        const day = this.padZero(date.getDate());
        const hours = this.padZero(date.getHours());
        const minutes = this.padZero(date.getMinutes());
        const seconds = this.padZero(date.getSeconds());

        return format
            .replace('YYYY', year)
            .replace('MM', month)
            .replace('DD', day)
            .replace('HH', hours)
            .replace('mm', minutes)
            .replace('ss', seconds);
    }
    formatSecondsTimestamp(timestamp, format = 'YYYY-MM-DD HH:mm:ss') {
        const time = parseInt(timestamp)

        const milliseconds = time * 1000;
        return this.formatTimestamp(milliseconds, format);
    }
    padZero(num) {
        return num.toString().padStart(2, '0');
    }
}
const localStorageService = new LocalStorageService();
const dateTimeUtils = new DateTimeUtils();

export function handleStr(str) {
    return str.substring(0, 10) + '.....'
}
export { localStorageService, dateTimeUtils };
