const fs = require('fs');
let c = fs.readFileSync('app/src/main/java/com/aftglw/devapi/SettingsActivity.kt', 'latin1');
c = c.replace('import com.aftglw.devapi.MoodModel\n', '');
c = c.replace(
  'sb.appendLine("Last Mood: ' + '$' + '{com.aftglw.devapi.MoodDetector.lastMood ?: "N/A"}")',
  'sb.appendLine("Last Mood: ' + '$' + '{com.aftglw.devapi.MoodDetector.lastMood ?: "N/A"} (source: ' + '$' + '{com.aftglw.devapi.MoodDetector.lastSource})")'
);
fs.writeFileSync('app/src/main/java/com/aftglw/devapi/SettingsActivity.kt', c, 'latin1');
console.log('SettingsActivity updated');
