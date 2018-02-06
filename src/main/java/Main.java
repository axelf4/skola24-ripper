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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Main {
	private static final String URL_NAME = "https://web.skola24.se/timetable/timetable-viewer/data/render";
	private static final String[] weekdays = {"Måndag", "Tisdag", "Onsdag", "Torsdag", "Fredag"};
	static final Pattern timeRegex = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
	static final Predicate<String> notTime = timeRegex.asPredicate().negate();

	public static void main(String[] args) throws IOException {
		Calendar calendar = Calendar.getInstance();
		List<VEvent> events = new ArrayList<>();
		for (int week = calendar.get(Calendar.WEEK_OF_YEAR), i = 0; i < 3; week = (week + 1) % 52, ++i) {
			System.out.println("---- Week " + week + " -----");
			JSONObject data = getJsonData(week);
			List<Lesson> lessons = parseLessons(data);
			events.addAll(getEventsFromLessons(lessons, week));
		}
		String calString = genICalendarFromEvents(events);
		try (PrintStream ps = new PrintStream(new FileOutputStream("cal.ics"))) {
			ps.print(calString);
		}
	}

	private static JSONObject getJsonData(int week) throws IOException {
		URL url = new URL(URL_NAME);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		con.setRequestProperty("Accept", "application/json");
		con.setRequestMethod("POST");

		JSONObject param = new JSONObject();
		param.put("divWidth", 1500);
		param.put("divHeight", 550);
		param.put("domain", "harryda.skola24.se");
		param.put("headerEnabled", false);
		param.put("selectedWeek", week);

		JSONObject selectedSchool = new JSONObject();
		selectedSchool.put("guid", "219254f7-917c-472e-ac23-6982156c6164");
		// selectedSchool.put("name", "Hulebäcksgymnasiet");
		param.put("selectedSchool", selectedSchool);

		// JSONObject selectedClass = new JSONObject();
		// selectedClass.put("guid", "c203b24e-de7d-4719-9258-e69500800523");
		// selectedClass.put("id", "NA15C");
		// selectedClass.put("isClass", true);
		// param.put("selectedClass", selectedClass);

		JSONObject selectedSignatures = new JSONObject();
		selectedSignatures.put("signature", "990811-6073");
		param.put("selectedSignatures", selectedSignatures);

		try (OutputStream os = con.getOutputStream()) {
			os.write(param.toString().getBytes("UTF-8"));
		}

		int result = con.getResponseCode();
		switch (result) {
			case HttpURLConnection.HTTP_OK:
				try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
					StringBuilder builder = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						builder.append(line).append('\n');
					}
					JSONObject response = new JSONObject(builder.toString());
					return response.getJSONObject("data");
				}
			default:
				System.out.println(con.getResponseMessage());
				return null;
		}
	}

	private static List<VEvent> getEventsFromLessons(List<Lesson> lessons, int week) {
		final Calendar c = Calendar.getInstance();
		c.set(Calendar.WEEK_OF_YEAR, week);
		c.set(Calendar.SECOND, 0);

		return lessons.stream().map(lesson -> {
			VEvent event = new VEvent();
			Summary summary = event.setSummary(lesson.text);
			summary.setLanguage("sv-SE");
			event.setColor(new Color(lesson.color));

			c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY + lesson.dayIndex);

			c.set(Calendar.HOUR_OF_DAY, lesson.start.hour);
			c.set(Calendar.MINUTE, lesson.start.minute);
			event.setDateStart(c.getTime(), true);

			c.set(Calendar.HOUR_OF_DAY, lesson.end.hour);
			c.set(Calendar.MINUTE, lesson.end.minute);
			event.setDateEnd(c.getTime(), true);

			return event;
		}).collect(Collectors.toList());
	}

	private static String genICalendarFromEvents(List<VEvent> events) {
		ICalendar ical = new ICalendar();
		events.forEach(ical::addEvent);
		return Biweekly.write(ical).go();
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

	/**
	 * Finds the time stamps for a lesson.
	 *
	 * @param texts
	 * @param box   The containing box of the lesson.
	 * @param type
	 * @return
	 */
	private static Time findTimeFromPoint(List<Text> texts, Box box, LessonTime type) {
		Point p = type == LessonTime.START ?
				new Point(box.x, box.y)
				: new Point(box.x + box.width, box.y + box.height);

		Optional<WeightedText> timeText = texts.stream()
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

					// System.out.println("score: " + score + " time: " + timeText.timeText + " dx: " + dx + " dy: " + dy);

					return new WeightedText(text, score);
				})
				.sorted(Comparator.comparingInt(a -> a.weight))
				.findFirst();
		if (!timeText.isPresent()) throw new AssertionError();
		return new Time(timeText.get().text.text);
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

	enum LessonTime {
		START, END
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

	static class Time {
		final int hour, minute;

		Time(int hour, int minute) {
			this.hour = hour;
			this.minute = minute;
		}

		Time(String s) {
			Matcher m = timeRegex.matcher(s);
			if (!m.find()) throw new AssertionError();
			hour = Integer.valueOf(m.group(1));
			minute = Integer.valueOf(m.group(2));
		}

		@Override
		public String toString() {
			return String.format("%d:%02d", hour, minute);
		}
	}

	private static class WeightedText {
		final Text text;
		final int weight;

		WeightedText(Text text, int weight) {
			this.text = text;
			this.weight = weight;
		}
	}

	static class Lesson {
		String text;
		int dayIndex;
		Time start, end;
		String color;
	}
}
