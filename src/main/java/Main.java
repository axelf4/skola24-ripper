import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.property.Color;
import biweekly.property.Summary;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Main {
	static final String URL_NAME = "https://web.skola24.se/timetable/timetable-viewer/data/render";

	static final String[] weekdays = { "Måndag", "Tisdag", "Onsdag", "Torsdag", "Fredag" };

	public static void main(String[] args) throws IOException {
		URL url = new URL(URL_NAME);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		con.setRequestProperty("Accept", "application/json; q=0.01");
		con.setRequestMethod("POST");
		con.setRequestProperty("Host", "web.skola24.se");
		con.setRequestProperty("Referer", "https://web.skola24.se/timetable/timetable-viewer/harryda.skola24.se/Huleb%C3%A4cksgymnasiet/class/NA15A/");
		con.setRequestProperty("Cookie", "ASP.NET_SessionId=2g45y51ncq352vkvxlacnca2");

		JSONObject param = new JSONObject();
		param.put("divWidth", 1500);
		param.put("divHeight", 550);
		param.put("domain", "harryda.skola24.se");
		param.put("headerEnabled", false);
		param.put("selectedWeek", 5);

		JSONObject selectedSchool = new JSONObject();
		selectedSchool.put("guid", "219254f7-917c-472e-ac23-6982156c6164");
		selectedSchool.put("name", "Hulebäcksgymnasiet");
		param.put("selectedSchool", selectedSchool);

		JSONObject selectedClass = new JSONObject();
		selectedClass.put("guid", "c203b24e-de7d-4719-9258-e69500800523");
		selectedClass.put("id", "NA15A");
		selectedClass.put("isClass", true);
		param.put("selectedClass", selectedClass);

		OutputStream os = con.getOutputStream();
		os.write(param.toString().getBytes("UTF-8"));
		os.close();

		int result = con.getResponseCode();
		switch (result) {
			case HttpURLConnection.HTTP_OK:
				BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
				StringBuilder builder = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					builder.append(line).append('\n');
				}
				br.close();
				JSONObject response = new JSONObject(builder.toString());
				JSONObject data = response.getJSONObject("data");
				List<Lesson> lessons = parseLessons(data);
				String calString = genLessonsCalendar(lessons);
				try (PrintStream ps = new PrintStream(new FileOutputStream("cal.ics"))) {
					ps.print(calString);
				}

				break;
			default:
				System.out.println(con.getResponseMessage());
		}
	}

	private static String genLessonsCalendar(List<Lesson> lessons) {
		ICalendar ical = new ICalendar();

		lessons.stream().map(lesson -> {
			VEvent event = new VEvent();
			Summary summary = event.setSummary(lesson.text);
			summary.setLanguage("sv-SE");
			event.setColor(new Color(lesson.color));

			Calendar c1 = Calendar.getInstance();
			c1.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY + lesson.dayIndex);
			c1.set(Calendar.HOUR_OF_DAY, lesson.start.hour);
			c1.set(Calendar.MINUTE, lesson.start.minute);
			c1.set(Calendar.SECOND, 0);
			event.setDateStart(c1.getTime(), true);

			Calendar c2 = Calendar.getInstance();
			c2.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY + lesson.dayIndex);
			c2.set(Calendar.HOUR_OF_DAY, lesson.end.hour);
			c2.set(Calendar.MINUTE, lesson.end.minute);
			c1.set(Calendar.SECOND, 0);
			event.setDateEnd(c2.getTime(), true);

			return event;
		}).forEach(ical::addEvent);

		return Biweekly.write(ical).go();
	}

	static class Text {
		final int x, y;
		final String fcolor;
		final double fontsize;
		final int id;
		final String text;
		// boolean bold, italic;

		Text(JSONObject obj) {
			x = obj.getInt("x");
			y = obj.getInt("y");
			fcolor = obj.getString("fcolor");
			fontsize = obj.getDouble("fontsize");
			id = obj.getInt("id");
			text = obj.getString("text");
		}
	}

	static class Box {
		int x, y, width, height;
		String bcolor, fcolor;
		int id;
		String type;

		Box(JSONObject obj) {
			x = obj.getInt("x");
			y = obj.getInt("y");
			width = obj.getInt("width");
			height = obj.getInt("height");
			bcolor = obj.getString("bcolor");
			fcolor = obj.getString("fcolor");
			id = obj.getInt("id");
			type = obj.getString("type");
		}

		boolean contains(Point p) {
			return p.x >= x && p.x < x + width && p.y >= y && p.y < y + height;
		}
	}

	public static <T> Collector<T, ?, T> singletonCollector() {
		return Collectors.collectingAndThen(
				Collectors.toList(),
				list -> {
					if (list.size() != 1) {
						throw new IllegalStateException();
					}
					return list.get(0);
				}
		);
	}

	static final Pattern timeRegex = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
	static final Predicate<String> notTime = timeRegex.asPredicate().negate();

	static class Time {
		final int hour, minute;

		Time(int hour, int minute) {
			this.hour = hour;
			this.minute = minute;
		}

		Time(String s) {
			Matcher m = timeRegex.matcher(s);
			m.find();
			hour = Integer.valueOf(m.group(1));
			minute = Integer.valueOf(m.group(2));
		}

		@Override
		public String toString() {
			return String.format("%d:%02d", hour, minute);
		}
	}

	enum LessonTime {
		START, END
	}

	private static class WeightedText {
		final Text text;
		final int weight;

		public WeightedText(Text text, int weight) {
			this.text = text;
			this.weight = weight;
		}
	}

	/**
	 * Finds the time stamps for a lesson.
	 * @param texts
	 * @param box The containing box of the lesson.
	 * @param type
	 * @return
	 */
	static Time findTimeFromPoint(List<Text> texts, Box box, LessonTime type) {
		Point p = type == LessonTime.START ?
				new Point(box.x, box.y)
				: new Point(box.x + box.width, box.y + box.height);

		return new Time(texts.stream()
				// Only consider time stamps
				.filter(text -> timeRegex.asPredicate().test(text.text))
				.map(text -> {
					// Calculate a score based on positional difference to point p
					int dx = Math.abs(text.x - p.x),
							dy = Math.abs(text.y + (int) (0.5 * text.fontsize) - p.y);

					int score = dx
							// Always stay relatively close on the y-axis
							// Any difference should only come from centering the label
							+ 5 * (int) Math.pow(Math.abs(dy), 3);

					// System.out.println("score: " + score + " time: " + text.text + " dx: " + dx + " dy: " + dy);

					return new WeightedText(text, score);
				})
				.sorted(Comparator.comparingInt(a -> a.weight))
				.findFirst().get().text.text);
	}

	static class Lesson {
		String text;
		int dayIndex;
		Time start, end;
		String color;
	}

	private static List<Lesson> parseLessons(JSONObject data) {
		JSONArray textList = data.getJSONArray("textList"),
				boxList = data.getJSONArray("boxList");
		List<Text> texts = new ArrayList<>(textList.length());
		List<Box> boxes = new ArrayList<>(boxList.length());
		for (int i = 0; i < textList.length(); ++i) {
			texts.add(new Text(textList.getJSONObject(i)));
		}
		for (int i = 0; i < boxList.length(); ++i)
			boxes.add(new Box(boxList.getJSONObject(i)));

		// Find weekdays labels and their boxes to determine day boundaries
		Box[] weekdayBoxes = Arrays.stream(weekdays)
				.map(day -> texts.stream().filter(text -> text.text.startsWith(day)).collect(singletonCollector()))
				.map(text -> new Point(text.x, text.y))
				.map(p -> boxes.stream().filter(box -> box.contains(p)).collect(singletonCollector()))
				.toArray(Box[]::new);

		List<Lesson> lessons = new ArrayList<>();

		// Find boxes with solid outline underneath weekday columns
		for (int i = 0; i < weekdays.length; ++i) {
			System.out.println("--- " + weekdays[i] + " ---");
			Box weekdayBox = weekdayBoxes[i];

			List<Box> lessonBoxes = boxes.stream()
					// Black outline
					.filter(box -> box.fcolor.equals("#000000"))
					// Do not include the background box
					.filter(box -> !box.bcolor.equals("#CCCCCC"))
					// Filter based on position
					.filter(box -> box.y >= weekdayBox.y + weekdayBox.height)
					// If 90% of the box is inside the day column
					.filter(box -> Math.min(box.x + box.width, weekdayBox.x + weekdayBox.width) - Math.max(box.x, weekdayBox.x) >= 0.9f * box.width)
					.collect(Collectors.toList());

			// Find strings about lesson
			for (Box lessonBox : lessonBoxes) {
				Time start = findTimeFromPoint(texts, lessonBox, LessonTime.START);
				Time end = findTimeFromPoint(texts, lessonBox, LessonTime.END);

				String lessonText = texts.stream()
						.filter(text -> lessonBox.contains(new Point(text.x, text.y)))
						.map(text -> text.text)
						.filter(notTime)
						// TODO add system for intelligent string assembly based on positions of subtexts
						.collect(Collectors.joining(" "))
						// Merge multiple whitespace
						.replaceAll("\\s+", " ");
				System.out.println("---Lesson, color: " + lessonBox.bcolor + " start: " + start + " end: " + end);
				System.out.println("\t" + lessonText);

				Lesson lesson = new Lesson();
				lesson.text = lessonText;
				lesson.color = HexToCSSColor.getCSSColorFromHex(lessonBox.bcolor);
				lesson.dayIndex = i;
				lesson.start = start;
				lesson.end = end;
				lessons.add(lesson);
			}
		}

		return lessons;
	}
}
