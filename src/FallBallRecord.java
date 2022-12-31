import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SpringLayout;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class PlayerStat {
	int participationCount; // rate 分母(今回log分)。
	int winCount; // rate 分子(今回log分)。
	int winStreak;
	int totalParticipationCount; // rate 分母。
	int totalWinCount; // rate 分子。

	public double getRate() {
		return Core.calRate(winCount, participationCount);
	}

	public boolean setWin(Round r, boolean win, int score) {
		if (win) {
			if (!r.history)
				winCount += 1;
			totalWinCount += 1;
			winStreak += 1;
		} else {
			winStreak = 0;
		}
		return win;
	}

	public void reset() {
		totalParticipationCount = totalWinCount = participationCount = winCount = winStreak = 0;
	}
}

//各ラウンドのプレイヤー戦績
class Player {
	Round round;
	String name; // for user identication
	String platform;
	int id; // id of current round (diferrent for each rounds)
	int objectId; // object id of current round (for score log)
	int squadId;
	int partyId;
	int teamId;
	int ranking; // rank of current round

	Boolean qualified;
	int score; // ラウンド中のスコアあるいは順位スコア
	int finalScore = -1; // ラウンド終了後に出力されたスコア

	Player(int id) {
		this.id = id;
	}

	// squad を考慮したクリア/脱落
	boolean isQualified() {
		return qualified == Boolean.TRUE;
	}

	public String toString() {
		return name + "(" + platform + ")";
	}
}

class Round {
	final Match match;
	final Date id;
	final String name;
	boolean isFinal;
	String roundName2; // より詳細な内部名
	boolean fixed; // ステージ完了まで読み込み済み
	Date start;
	Date end;
	Date topFinish;
	Date myFinish;
	int myPlayerId;
	int[] teamScore;
	int playerCount;
	Map<String, Player> byName = new HashMap<String, Player>();
	Map<Integer, Player> byId = new HashMap<Integer, Player>();
	boolean history;

	public Round(String name, Date id, boolean isFinal, Match match) {
		this.name = name;
		this.id = id;
		this.isFinal = isFinal;
		this.match = match;
	}

	public RoundDef getDef() {
		return RoundDef.get(name);
	}

	public void add(Player p) {
		p.round = this;
		synchronized (Core.listLock) {
			if (!byId.containsKey(p.id))
				playerCount += 1;
			byId.put(p.id, p);
			if (p.name != null)
				byName.put(p.name, p);
		}
	}

	public void remove(String name) {
		synchronized (Core.listLock) {
			Player p = byName.get(name);
			if (p == null)
				return;
			byName.remove(name);
			byId.remove(p.id);
			playerCount -= 1;
		}
	}

	public Player getByObjectId(int id) {
		for (Player p : byId.values())
			if (p.objectId == id)
				return p;
		return null;
	}

	public boolean isSquad() {
		return byId.size() > 0 && byId.values().iterator().next().squadId != 0;
	}

	public int getTeamScore(int teamId) {
		if (teamScore == null || teamScore.length <= teamId)
			return 0;
		return teamScore[teamId];
	}

	public boolean isFallBall() {
		return "FallGuy_FallBall_5".equals(name);
	}

	public boolean isCustomFallBall() {
		return "event_only_fall_ball_template".equals(match.name);
	}

	public int getSubstancePlayerCount() {
		return playerCount / 2 * 2;
	}

	// 自分がクリアしたかどうか
	public Player getMe() {
		return byName.get("YOU");
	}

	public boolean isQualified() {
		Player p = getMe();
		return p != null && p.qualified == Boolean.TRUE;
	}

	// myFinish や end を渡して ms 取得
	public long getTime(Date o) {
		long t = o.getTime() - start.getTime();
		if (t < 0)
			t += 24 * 60 * 60 * 1000;
		return t;
	}

	public ArrayList<Player> byRank() {
		ArrayList<Player> list = new ArrayList<Player>(byId.values());
		Collections.sort(list, new Core.PlayerComparator(getDef().isHuntRace()));
		return list;
	}

	public boolean isFinal() {
		if (isFinal)
			return true;
		// isFinal だけでは決勝判定が不十分…
		if (roundName2 != null) {
			// 非ファイナルラウンドがファイナルとして出現した場合の検査
			if (roundName2.contains("_non_final"))
				return false;
			if (roundName2.endsWith("_final"))
				return true;
			if (roundName2.contains("only_finals"))
				return false;
			if (roundName2.contains("round_robotrampage_arena_2_ss2_show1_03"))
				return true;
			if (byId.size() > 8 && roundName2.contains("_survival"))
				return false;
			if (roundName2.contains("round_thin_ice_blast_ball_banger"))
				return false;
			//* squads final detection
			if (match.name.startsWith("squads_4") && byId.size() < 9) {
				if (roundName2.startsWith("round_jinxed_squads"))
					return true;
				if (roundName2.startsWith("round_territory_control_squads"))
					return true;
				if (roundName2.startsWith("round_fall_ball_squads"))
					return true;
				if (roundName2.startsWith("round_basketfall_squads"))
					return true;
				if ("round_territory_control_s4_show_squads".equals(roundName2))
					return true;
			}
			//*/
			if ("round_sports_suddendeath_fall_ball_02".equals(roundName2)) // GG
				return true;

			// FIXME: ファイナル向けラウンドが非ファイナルで出現した場合の検査が必要
			if ("round_thin_ice_pelican".equals(roundName2))
				return false;
			if (roundName2.matches("round_floor_fall_.*_0[12]$")) // hex trial
				return false;
			if (roundName2.matches("round_thin_ice_.*_0[12]$")) // thin ice trial
				return false;
			if (roundName2.matches("round_hexaring_.*_0[12]$")) // hexaring trial
				return false;
			if (roundName2.matches("round_blastball_.*_0[12]$")) // blastball trial
				return false;
			if (roundName2.matches("round_.+_event_.+")) // walnut event
				return false;
		}
		RoundDef def = RoundDef.get(name);
		if (def != null && def.isFinalNormally) // 通常ファイナルでしかでないステージならファイナルとみなす。
			return true;
		return false;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		Round o = (Round) obj;
		return id.equals(o.id);
	}

	@Override
	public String toString() {
		return getDef().getName();
	}
}

// 一つのショー
class Match {
	boolean fixed; // 完了まで読み込み済み
	String name;
	String ip;
	long pingMS;
	final Date start; // id として使用
	Date end;
	int winStreak;
	List<Round> rounds = new ArrayList<Round>();

	public Match(String name, Date start, String ip) {
		this.name = name;
		this.start = start;
		this.ip = ip;
	}

	@Override
	public int hashCode() {
		return start.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		Match o = (Match) obj;
		return start.equals(o.start);
	}

	@Override
	public String toString() {
		return name;
	}
}

enum RoundType {
	RACE, HUNT_SURVIVE, HUNT_RACE, SURVIVAL, TEAM
};

class RoundDef {

	public final String key;
	public final RoundType type;
	public final boolean isFinalNormally; // 通常はファイナルとして出現
	public final int teamCount;

	public RoundDef(String key, RoundType type) {
		this(key, type, false, 1);
	}

	// for team
	public RoundDef(String key, int teamCount) {
		this(key, RoundType.TEAM, false, teamCount);
	}

	public RoundDef(String key, RoundType type, boolean isFinal) {
		this(key, type, isFinal, 1);
	}

	private RoundDef(String key, RoundType type, boolean isFinal, int teamCount) {
		this.key = key;
		this.type = type;
		this.isFinalNormally = isFinal;
		this.teamCount = teamCount;
	}

	public String getName() {
		try {
			String name = Core.RES.getString(key);
			if (name == null)
				return key;
			return name;
		} catch (MissingResourceException w) {
			return key;
		}
	}

	public boolean isHuntRace() {
		return type == RoundType.HUNT_RACE;
	}

	static void add(RoundDef def) {
		roundNames.put(def.key, def);
	}

	static Map<String, RoundDef> roundNames = new HashMap<String, RoundDef>();
	static {
		add(new RoundDef("FallGuy_DoorDash", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_03", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_02_01", RoundType.RACE));
		add(new RoundDef("FallGuy_ChompChomp_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_01", RoundType.RACE));
		add(new RoundDef("FallGuy_SeeSaw_variant2", RoundType.RACE));
		add(new RoundDef("FallGuy_Lava_02", RoundType.RACE));
		add(new RoundDef("FallGuy_DodgeFall", RoundType.RACE));
		add(new RoundDef("FallGuy_TipToe", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_04", RoundType.RACE));
		add(new RoundDef("FallGuy_WallGuys", RoundType.RACE));
		add(new RoundDef("FallGuy_BiggestFan", RoundType.RACE));
		add(new RoundDef("FallGuy_IceClimb_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Tunnel_Race_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_06", RoundType.RACE));
		add(new RoundDef("FallGuy_ShortCircuit", RoundType.RACE));
		add(new RoundDef("FallGuy_HoverboardSurvival", RoundType.RACE));
		add(new RoundDef("FallGuy_SlimeClimb_2", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_07", RoundType.RACE));
		add(new RoundDef("FallGuy_DrumTop", RoundType.RACE));
		add(new RoundDef("FallGuy_SeeSaw360", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_08", RoundType.RACE));
		add(new RoundDef("FallGuy_PipedUp", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_05", RoundType.RACE));

		add(new RoundDef("FallGuy_TailTag_2", 1));
		add(new RoundDef("FallGuy_1v1_ButtonBasher", RoundType.HUNT_SURVIVE));
		add(new RoundDef("FallGuy_Hoops_Blockade", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_SkeeFall", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_Penguin_Solos", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_KingOfTheHill2", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_Airtime", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FollowTheLeader", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FollowTheLeader_UNPACKED", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_Block_Party", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_JumpClub_01", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_MatchFall", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_Tunnel_01", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_SnowballSurvival", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_FruitPunch", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_RobotRampage_Arena2", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_FruitBowl", RoundType.SURVIVAL));

		add(new RoundDef("FallGuy_TeamInfected", 2));
		add(new RoundDef("FallGuy_FallBall_5", 2));
		add(new RoundDef("FallGuy_Basketfall_01", 2));
		add(new RoundDef("FallGuy_TerritoryControl_v2", 2));
		add(new RoundDef("FallGuy_BallHogs_01", 3));
		add(new RoundDef("FallGuy_RocknRoll", 3));
		add(new RoundDef("FallGuy_Hoops_01", 3));
		add(new RoundDef("FallGuy_EggGrab", 3));
		add(new RoundDef("FallGuy_EggGrab_02", 3));
		add(new RoundDef("FallGuy_Snowy_Scrap", 3));
		add(new RoundDef("FallGuy_ChickenChase_01", 3));
		add(new RoundDef("FallGuy_ConveyorArena_01", 4));

		add(new RoundDef("FallGuy_Invisibeans", 2));
		add(new RoundDef("FallGuy_PumpkinPie", 2));

		add(new RoundDef("FallGuy_FallMountain_Hub_Complete", RoundType.RACE, true));
		add(new RoundDef("FallGuy_FloorFall", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_JumpShowdown_01", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_Crown_Maze_Topdown", RoundType.RACE, true));
		add(new RoundDef("FallGuy_Tunnel_Final", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_Arena_01", RoundType.HUNT_SURVIVE, true));
		add(new RoundDef("FallGuy_ThinIce", RoundType.SURVIVAL, true));

		add(new RoundDef("FallGuy_Gauntlet_09", RoundType.RACE));
		add(new RoundDef("FallGuy_ShortCircuit2", RoundType.RACE));
		add(new RoundDef("FallGuy_SpinRing", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_HoopsRevenge", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_1v1_Volleyfall", RoundType.HUNT_SURVIVE));
		add(new RoundDef("FallGuy_HexARing", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_BlastBall_ArenaSurvival", RoundType.SURVIVAL, true));

		add(new RoundDef("FallGuy_BlueJay_UNPACKED", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_SatelliteHoppers", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_10", RoundType.RACE));
		add(new RoundDef("FallGuy_Starlink", RoundType.RACE));
		add(new RoundDef("FallGuy_Hoverboard_Survival_2", RoundType.RACE));
		add(new RoundDef("FallGuy_PixelPerfect", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FFA_Button_Bashers", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_Tip_Toe_Finale", RoundType.RACE, true));
		add(new RoundDef("FallGuy_HexSnake", RoundType.SURVIVAL, true));
		// round_tiptoefinale

		add(new RoundDef("FallGuy_SlideChute", RoundType.RACE, false));
		add(new RoundDef("FallGuy_ wTheLine", RoundType.RACE, false));
		add(new RoundDef("FallGuy_SlippySlide", RoundType.HUNT_RACE, false));
		add(new RoundDef("FallGuy_BlastBallRuins", RoundType.SURVIVAL, false));
		add(new RoundDef("FallGuy_Kraken_Attack", RoundType.SURVIVAL, true));
	}

	public static RoundDef get(String name) {
		RoundDef def = roundNames.get(name);
		if (def == null)
			return new RoundDef(name, RoundType.RACE); // unknown stage
		return def;
	}
}

///////////////////////////////////////////////////////////////////////////////////////////////////////
// Core.rounds もとに実績達成判定
abstract class Achievement {
	public abstract boolean isCompleted();

	public abstract String toString();
}

class RoundCountAchievement extends Achievement {
	int count;

	public RoundCountAchievement(int count) {
		this.count = count;
	}

	@Override
	public boolean isCompleted() {
		return Core.filtered(r -> r.isFallBall() && r.isCustomFallBall() && r.getMe() != null).size() >= count;
	}

	@Override
	public String toString() {
		return count + " rounds played";
	}
}

class WinCountAchievement extends Achievement {
	int count;
	boolean overtimeOnly;

	public WinCountAchievement(int count) {
		this.count = count;
	}

	public WinCountAchievement(int count, boolean overtimeOnly) {
		this.count = count;
		this.overtimeOnly = overtimeOnly;
	}

	@Override
	public boolean isCompleted() {
		return Core
				.filtered(r -> r.isFallBall() && r.isCustomFallBall() && r.isQualified()
						&& (!overtimeOnly || r.getTime(r.myFinish) > 121000))
				.size() >= count;
	}

	@Override
	public String toString() {
		return count + " wins" + (overtimeOnly ? " at overtime round" : "");
	}
}

class StreakAchievement extends Achievement {
	int count;

	public StreakAchievement(int count) {
		this.count = count;
	}

	@Override
	public boolean isCompleted() {
		int maxStreak = 0;
		int streak = 0;
		for (Round r : Core.filtered(r -> r.isFallBall() && r.isCustomFallBall() && r.getMe() != null)) {
			if (r.isQualified()) {
				streak += 1;
				if (maxStreak < streak)
					maxStreak = streak;
			} else
				streak = 0;
		}
		return maxStreak >= count;
	}

	@Override
	public String toString() {
		return count + " wins streak";
	}
}

class RateAchievement extends Achievement {
	double rate;
	int limit;

	public RateAchievement(double rate, int limit) {
		this.rate = rate;
		this.limit = limit;
	}

	@Override
	public boolean isCompleted() {
		int winCount = 0;
		List<Round> targetRounds = new ArrayList<>();
		;
		for (Round r : Core.filtered(r -> r.isFallBall() && r.isCustomFallBall() && r.getMe() != null)) {
			targetRounds.add(r);
			winCount += r.isQualified() ? 1 : 0;
			if (limit > 0 && targetRounds.size() > limit) {
				Round o = targetRounds.remove(0);
				winCount -= o.isQualified() ? 1 : 0;
			}
			if (limit > 0 && targetRounds.size() >= limit && Core.calRate(winCount, targetRounds.size()) * 100 >= rate)
				return true;
		}
		if (limit > 0)
			return false;
		return Core.calRate(winCount, targetRounds.size()) * 100 >= rate;
	}

	@Override
	public String toString() {
		return rate + "% wins in " + limit + " rounds";
	}
}

///////////////////////////////////////////////////////////////////////////////////////////////////////
interface RoundFilter {
	boolean isEnabled(Round r);
}

class AllRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return true;
	}

	@Override
	public String toString() {
		return "ALL";
	}
}

class CustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.isCustomFallBall();
	}

	@Override
	public String toString() {
		return "CustomOnly";
	}
}

class NotCustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return !r.isCustomFallBall();
	}

	@Override
	public String toString() {
		return "NotCustomOnly";
	}
}

class Core {
	static Locale LANG;
	static ResourceBundle RES;
	static Object listLock = new Object();

	// utilities
	public static double calRate(int win, int round) {
		if (round == 0)
			return 0;
		BigDecimal win_dec = new BigDecimal(win);
		BigDecimal round_dec = new BigDecimal(round);
		BigDecimal rate = win_dec.divide(round_dec, 4, BigDecimal.ROUND_HALF_UP);
		rate = rate.multiply(new BigDecimal("100"));
		rate = rate.setScale(2, RoundingMode.DOWN);
		return rate.doubleValue();
	}

	public static String pad(int v) {
		return String.format("%2d", v);
	}

	public static String pad0(int v) {
		return String.format("%02d", v);
	}

	public static int[] intArrayFromString(String string) {
		String[] strings = string.replace("[", "").replace("]", "").split(", ");
		if (strings.length == 0)
			return null;
		int result[] = new int[strings.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = Integer.parseInt(strings[i]);
		}
		return result;
	}

	/*
	static String dump(byte[] bytes) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < bytes.length; i += 1) {
			b.append(Integer.toString(bytes[i] & 0xff, 16)).append(' ');
		}
		return b.toString();
	}
	*/

	//////////////////////////////////////////////////////////////
	public static String currentServerIp;
	public static RoundFilter filter = null;
	public static int limit = 0;
	public static final List<Match> matches = new ArrayList<>();
	public static final List<Round> rounds = new ArrayList<>();
	public static Map<String, Map<String, String>> servers = new HashMap<>();
	public static PlayerStat stat = new PlayerStat();
	public static List<Achievement> achievements = new ArrayList<>();

	static DateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS");
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		achievements.add(new RateAchievement(60, 10));
		achievements.add(new RateAchievement(65, 10));
		achievements.add(new RateAchievement(50, 30));
		achievements.add(new RateAchievement(55, 30));
		achievements.add(new RateAchievement(60, 30));
		achievements.add(new RateAchievement(65, 30));
		achievements.add(new RateAchievement(70, 30));
		achievements.add(new RateAchievement(75, 30));
		achievements.add(new RateAchievement(60, 50));
		achievements.add(new RateAchievement(65, 50));
		achievements.add(new RateAchievement(70, 50));
		achievements.add(new RateAchievement(75, 50));
		achievements.add(new RoundCountAchievement(10));
		achievements.add(new RoundCountAchievement(20));
		achievements.add(new RoundCountAchievement(100));
		achievements.add(new RoundCountAchievement(500));
		achievements.add(new RoundCountAchievement(1000));
		achievements.add(new RoundCountAchievement(2000));
		achievements.add(new RoundCountAchievement(4000));
		achievements.add(new RoundCountAchievement(6000));
		achievements.add(new RoundCountAchievement(10000));
		achievements.add(new WinCountAchievement(5));
		achievements.add(new WinCountAchievement(10));
		achievements.add(new WinCountAchievement(300));
		achievements.add(new WinCountAchievement(600));
		achievements.add(new WinCountAchievement(1500));
		achievements.add(new WinCountAchievement(3000));
		achievements.add(new WinCountAchievement(2, true));
		achievements.add(new WinCountAchievement(50, true));
		achievements.add(new WinCountAchievement(150, true));
		achievements.add(new StreakAchievement(3));
		achievements.add(new StreakAchievement(4));
		achievements.add(new StreakAchievement(5));
		achievements.add(new StreakAchievement(7));
		achievements.add(new StreakAchievement(10));
	}

	public static void load() {
		rounds.clear();
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream("stats.tsv"), StandardCharsets.UTF_8))) {
			String line;
			Match m = null;
			while ((line = in.readLine()) != null) {
				String[] d = line.split("\t");
				if (d.length < 5)
					continue;

				if ("M".equals(d[0])) {
					Date matchStart = f.parse(d[1]);
					m = new Match(d[2], matchStart, d[3]);
					m.pingMS = Integer.parseInt(d[4]);
					addMatch(m);
					continue;
				}
				if (d.length < 8)
					continue;
				Round r = new Round(d[2], f.parse(d[1]), "true".equals(d[4]), m);
				r.fixed = true;
				r.history = true;
				r.start = f.parse(d[1]); // 本当の start とは違う
				if (d[4].length() > 0)
					r.myFinish = new Date(r.start.getTime() + Long.parseUnsignedLong(d[4]));
				Player p = new Player(0);
				p.name = "YOU";
				p.qualified = "true".equals(d[5]);
				p.teamId = Integer.parseInt(d[6]);
				if (d.length > 7)
					r.teamScore = Core.intArrayFromString(d[7]);
				r.add(p);
				rounds.add(r);
				r.playerCount = Integer.parseInt(d[3]);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public static void save() {
		try (PrintWriter out = new PrintWriter(
				new OutputStreamWriter(new FileOutputStream("stats.tsv"), StandardCharsets.UTF_8),
				false)) {
			Match currentMatch = null;
			for (Round r : rounds) {
				if (!r.isFallBall())
					continue;
				if (currentMatch == null || !currentMatch.equals(r.match)) {
					currentMatch = r.match;
					// write match line
					out.print("M"); // 0
					out.print("\t");
					out.print(f.format(currentMatch.start)); // 0
					out.print("\t");
					out.print(currentMatch.name); // 1
					out.print("\t");
					out.print(currentMatch.ip); // 2
					out.print("\t");
					out.print(currentMatch.pingMS); // 3
					out.println();
				}
				Player p = r.getMe();
				if (p == null)
					continue;
				out.print("r"); // 0
				out.print("\t");
				out.print(f.format(r.id)); // 1
				out.print("\t");
				out.print(r.name); // 2
				out.print("\t");
				out.print(r.playerCount); // 3
				out.print("\t");
				if (r.myFinish != null)
					out.print(r.getTime(r.myFinish)); // 4
				else if (r.end != null)
					out.print(r.getTime(r.end)); // 4

				out.print("\t");
				out.print(p.isQualified()); // 5
				out.print("\t");
				out.print(p.teamId); // 6
				out.print("\t");
				if (r.teamScore != null)
					out.print(Arrays.toString(r.teamScore)); // 7
				out.println();
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	public static void addMatch(Match m) {
		synchronized (listLock) {
			if (matches.contains(m))
				matches.remove(m);
			// 直前のマッチのラウンド０だったら除去
			if (matches.size() > 1 && getCurrentMatch().rounds.size() == 0)
				matches.remove(matches.size() - 1);
			matches.add(m);
		}
	}

	public static void addRound(Round r) {
		synchronized (listLock) {
			if (rounds.contains(r))
				rounds.remove(r);
			rounds.add(r);
			getCurrentMatch().rounds.add(r);
		}
	}

	public static Match getCurrentMatch() {
		if (matches.size() == 0)
			return null;
		return matches.get(matches.size() - 1);
	}

	public static Round getCurrentRound() {
		if (rounds.size() == 0)
			return null;
		return rounds.get(rounds.size() - 1);
	}

	// 新しい順にする
	public static List<Round> filtered(RoundFilter f) {
		List<Round> result = new ArrayList<>();
		for (ListIterator<Round> i = rounds.listIterator(rounds.size()); i.hasPrevious();) {
			Round r = i.previous();
			if (f != null && !f.isEnabled(r))
				continue;
			result.add(r);
		}
		return result;
	}

	public static void updateStats() {
		synchronized (listLock) {
			stat.reset();
			int c = 0;
			for (Round r : filtered(filter)) {
				if (!r.fixed)
					continue;

				// ignore except the fall ball
				if (!r.isFallBall())
					continue;

				// このラウンドの参加者の結果を反映
				for (Player p : r.byId.values()) {
					if (!"YOU".equals(p.name))
						continue;
					if (!r.history)
						stat.participationCount += 1; // 参加 round 数
					stat.totalParticipationCount += 1; // 参加 round 数
					stat.setWin(r, p.isQualified(), 1);
				}
				c += 1;
				if (limit > 0 && c >= limit)
					return;
			}
		}
	}

	static class PlayerComparator implements Comparator<Player> {
		boolean isHunt;

		PlayerComparator(boolean hunt) {
			isHunt = hunt;
		}

		@Override
		public int compare(Player p1, Player p2) {
			if (p1.ranking > 0 && p2.ranking == 0)
				return -1;
			if (p2.ranking > 0 && p1.ranking == 0)
				return 1;
			if (p1.ranking > 0 && p2.ranking > 0)
				return (int) Math.signum(p1.ranking - p2.ranking);
			if (!isHunt) { // hunt 系の finalScore がバグっていて獲得スコアを出してきてしまう。これでは正確な順位付けができない。
				int v = (int) Math.signum(p2.finalScore - p1.finalScore);
				if (v != 0)
					return v;
			}
			return (int) Math.signum(p2.score - p1.score);
		}
	}
}

// wrap tailer
class FGReader extends TailerListenerAdapter {
	public interface Listener {
		void showUpdated();

		void roundStarted();

		void roundUpdated();

		void roundDone();
	}

	Tailer tailer;
	Thread thread;
	Listener listener;

	public FGReader(File log, Listener listener) {
		tailer = new Tailer(log, StandardCharsets.UTF_8, this, 400, false, false, 8192);
		this.listener = listener;
	}

	public void start() {
		thread = new Thread(tailer);
		thread.start();
	}

	public void stop() {
		tailer.stop();
		thread.interrupt();
	}

	//////////////////////////////////////////////////////////////////
	enum ReadState {
		SHOW_DETECTING, ROUND_DETECTING, MEMBER_DETECTING, RESULT_DETECTING
	}

	ReadState readState = ReadState.SHOW_DETECTING;
	int myObjectId = 0;
	int topObjectId = 0;
	int qualifiedCount = 0;
	int eliminatedCount = 0;
	boolean isFinal = false;
	long prevNetworkCheckedTime = System.currentTimeMillis();

	Timer survivalScoreTimer;

	@Override
	public void handle(String line) {
		try {
			parseLine(line);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	static Pattern patternServer = Pattern
			.compile("\\[StateConnectToGame\\] We're connected to the server! Host = ([^:]+)");

	static Pattern patternShowName = Pattern.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	//	static Pattern patternShow = Pattern
	//			.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	//static Pattern patternMatchStart = Pattern.compile("\\[StateMatchmaking\\] Begin ");
	static Pattern patternRoundName = Pattern.compile(
			"\\[StateGameLoading\\] Loading game level scene ([^\\s]+) - frame (\\d+)");
	static Pattern patternLoadedRound = Pattern
			.compile("\\[StateGameLoading\\] Finished loading game level, assumed to be ([^.]+)\\.");

	static Pattern patternLocalPlayerId = Pattern
			.compile(
					"\\[ClientGameManager\\] Handling bootstrap for local player FallGuy \\[(\\d+)\\] \\(FG.Common.MPGNetObject\\), playerID = (\\d+), squadID = (\\d+)");
	static Pattern patternPlayerObjectId = Pattern.compile(
			"\\[ClientGameManager\\] Handling bootstrap for [^ ]+ player FallGuy \\[(\\d+)\\].+, playerID = (\\d+)");
	static Pattern patternPlayerSpawn = Pattern.compile(
			"\\[CameraDirector\\] Adding Spectator target (.+) \\((.+)\\) with Party ID: (\\d*)  Squad ID: (\\d+) and playerID: (\\d+)");
	//static Pattern patternPlayerSpawnFinish = Pattern.compile("\\[ClientGameManager\\] Finalising spawn for player FallGuy \\[(\\d+)\\] (.+) \\((.+)\\) ");

	static Pattern patternScoreUpdated = Pattern.compile("Player (\\d+) score = (\\d+)");
	static Pattern patternTeamScoreUpdated = Pattern.compile("Team (\\d+) score = (\\d+)");
	static Pattern patternPlayerResult = Pattern.compile(
			"ClientGameManager::HandleServerPlayerProgress PlayerId=(\\d+) is succeeded=([^\\s]+)");

	static Pattern patternPlayerResult2 = Pattern.compile(
			"-playerId:(\\d+) points:(\\d+) isfinal:([^\\s]+) name:");

	static DateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	static Date getTime(String line) {
		try {
			Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			Calendar parsed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			parsed.setTime(f.parse(line.substring(0, 12)));
			c.set(Calendar.HOUR_OF_DAY, parsed.get(Calendar.HOUR_OF_DAY));
			c.set(Calendar.MINUTE, parsed.get(Calendar.MINUTE));
			c.set(Calendar.SECOND, parsed.get(Calendar.SECOND));
			c.set(Calendar.MILLISECOND, parsed.get(Calendar.MILLISECOND));
			return c.getTime();
		} catch (ParseException e) {
			//e.printStackTrace();
		}
		return new Date();
	}

	private void parseLine(String line) {
		Round r = Core.getCurrentRound();
		/*
		if (line.contains("[UserInfo] Player Name:")) {
			String[] sp = line.split("Player Name: ", 2);
			Core.myNameFull = sp[1];
		}
		*/
		Matcher m = patternServer.matcher(line);
		if (m.find()) {
			String showName = "_";
			String ip = m.group(1);
			Match match = new Match(showName, getTime(line), ip);
			Core.addMatch(match);
			System.out.println("DETECT SHOW STARTING");
			readState = ReadState.ROUND_DETECTING;

			if (!ip.equals(Core.currentServerIp)) {
				System.out.println("new server detected: " + ip);
				long now = System.currentTimeMillis();
				if (match.pingMS == 0 || prevNetworkCheckedTime + 60 * 1000 < now) {
					Core.currentServerIp = ip;
					prevNetworkCheckedTime = now;
					// ping check
					try {
						InetAddress address = InetAddress.getByName(ip);
						boolean res = address.isReachable(3000);
						match.pingMS = System.currentTimeMillis() - now;
						System.out.println("PING " + res + " " + match.pingMS);
						Map<String, String> server = Core.servers.get(ip);
						if (server == null) {
							ObjectMapper mapper = new ObjectMapper();
							JsonNode root = mapper.readTree(new URL("http://ip-api.com/json/" + ip));
							server = new HashMap<String, String>();
							server.put("country", root.get("country").asText());
							server.put("regionName", root.get("regionName").asText());
							server.put("city", root.get("city").asText());
							server.put("timezone", root.get("timezone").asText());
							Core.servers.put(ip, server);
						}
						System.err.println(ip + " " + match.pingMS + " " + server.get("timezone") + " "
								+ server.get("city"));
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			listener.showUpdated();
		}
		m = patternLocalPlayerId.matcher(line);
		if (m.find()) {
			r.myPlayerId = Integer.parseUnsignedInt(m.group(2));
		}
		switch (readState) {
		case SHOW_DETECTING: // start show or round detection
		case ROUND_DETECTING: // start round detection
			m = patternShowName.matcher(line);
			if (m.find()) {
				String showName = m.group(1);
				Core.getCurrentMatch().name = showName;
				listener.showUpdated();
				break;
			}
			if (line.contains("isFinalRound=")) {
				isFinal = line.contains("isFinalRound=True");
				break;
			}
			m = patternRoundName.matcher(line);
			if (m.find()) {
				String roundName = m.group(1);
				//long frame = Long.parseUnsignedLong(m.group(2)); // FIXME: round id のほうが適切
				Core.addRound(new Round(roundName, getTime(line), isFinal, Core.getCurrentMatch()));
				r = Core.getCurrentRound();
				System.out.println("DETECT STARTING " + roundName);
				//readState = ReadState.MEMBER_DETECTING;
			}
			m = patternLoadedRound.matcher(line);
			if (m.find()) {
				String roundName2 = m.group(1);
				r.roundName2 = roundName2;
				System.out.println("DETECT STARTING " + roundName2);
				readState = ReadState.MEMBER_DETECTING;
			}
			break;
		case MEMBER_DETECTING: // join detection
			// 本来 playerId, name が先に検出されるべきだが、playerId, objectId が先に出力されうるためどちらが先でも対応できるようにする。
			m = patternPlayerObjectId.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int playerId = Integer.parseUnsignedInt(m.group(2));
				Player p = r.byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
					r.add(p);
				}
				p.objectId = playerObjectId;
				// System.out.println("playerId=" + playerId + " objectId=" + playerObjectId);
				break;
			}
			m = patternPlayerSpawn.matcher(line);
			if (m.find()) {
				String name = m.group(1);
				String platform = m.group(2);
				int partyId = m.group(3).length() == 0 ? 0 : Integer.parseUnsignedInt(m.group(3)); // 空文字列のことあり
				int squadId = Integer.parseUnsignedInt(m.group(4));
				int playerId = Integer.parseUnsignedInt(m.group(5));
				// win...xxx のような末尾３文字だけになった
				String playerName = platform + name;

				Player p = r.byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
				}
				p.partyId = partyId;
				p.squadId = squadId;
				p.name = playerName + p.id;
				p.platform = platform;
				r.add(p);
				if (r.myPlayerId == p.id) {
					p.name = "YOU";
					r.add(p);
					//Core.myName = p.name;
				}

				System.out.println(r.byId.size() + " Player " + playerName + " (id=" + playerId
						+ " squadId=" + squadId + ") spwaned.");
				listener.roundUpdated();
				// 現在の自分の objectId 更新
				// if (Core.myName.equals(p.name))
				if (r.myPlayerId == p.id)
					myObjectId = p.objectId;
				break;
			}
			// こちらで取れる名前は旧名称だった…
			/* この行での名前出力がなくなっていた
			m = patternPlayerSpawnFinish.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				String name = m.group(2);
				Player p = r.getByObjectId(playerObjectId);
				if (p != null) {
					if (name.length() == 5) // 名前が短いと'a...b'のように前後１文字に短縮されている。元の名前の末尾３文字を活かす
						p.name = name.substring(0, 4) + p.name.substring(p.name.length() - 3);
					else
						p.name = name;
					if (r.myPlayerId == p.id)
						Core.myName = p.name;
				}
				r.add(p);
				break;
			}
			*/
			if (line.contains("[StateGameLoading] Starting the game.")) {
				listener.roundStarted();
			}
			if (line.contains("[GameSession] Changing state from Countdown to Playing")) {
				r.start = getTime(line);
				topObjectId = 0;
				listener.roundStarted();
				qualifiedCount = eliminatedCount = 0; // reset
				readState = ReadState.RESULT_DETECTING;
				if (r.getDef().type == RoundType.SURVIVAL) {
					survivalScoreTimer = new Timer();
					survivalScoreTimer.scheduleAtFixedRate(new TimerTask() {
						@Override
						public void run() {
							for (Player p : Core.getCurrentRound().byId.values()) {
								if (p.qualified == null)
									p.score += 1;
							}
							listener.roundUpdated();
						}
					}, 1000, 1000);
				}
				break;
			}
			if (line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMatchmaking] Begin matchmaking")) {
				System.out.println("DETECT BACK TO LOBBY");
				Core.rounds.remove(Core.rounds.size() - 1); // delete current round
				readState = ReadState.SHOW_DETECTING;
				break;
			}
			break;
		case RESULT_DETECTING: // result detection
			// score update duaring round
			m = patternScoreUpdated.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				Player player = r.getByObjectId(playerObjectId);
				if (player != null) {
					if (player.score != score) {
						System.out.println(player + " score " + player.score + " -> " + score);
						player.score = score;
						listener.roundUpdated();
					}
				}
				break;
			}
			m = patternTeamScoreUpdated.matcher(line);
			if (m.find()) {
				int teamId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				if (r.teamScore == null)
					r.teamScore = new int[r.getDef().teamCount];
				r.teamScore[teamId] = score;
				listener.roundUpdated();
				break;
			}
			// finish time handling
			if (line.contains("[ClientGameManager] Handling unspawn for player FallGuy ")) {
				if (topObjectId == 0) {
					topObjectId = Integer
							.parseInt(line.replaceFirst(".+Handling unspawn for player FallGuy \\[(\\d+)\\].*", "$1"));
					r.topFinish = getTime(line);
				}
				if (line.contains("[ClientGameManager] Handling unspawn for player FallGuy [" + myObjectId + "] ")) {
					r.myFinish = getTime(line);
				}
			}

			// qualified / eliminated
			m = patternPlayerResult.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				boolean succeeded = "True".equals(m.group(2));
				Player player = r.byId.get(playerId);
				if (!succeeded)
					System.out.print("Eliminated for " + playerId + " ");
				if (player != null) {
					player.qualified = succeeded;
					if (succeeded) {
						// スコア出力がない場合の仮スコア付
						switch (RoundDef.get(r.name).type) {
						case RACE:
							player.score += r.byId.size() - qualifiedCount;
							break;
						case HUNT_RACE:
						case HUNT_SURVIVE:
						case SURVIVAL:
						case TEAM:
							if (player.score == 0)
								player.score = 1;
							break;
						}
						qualifiedCount += 1;
						player.ranking = qualifiedCount;
						System.out.println("Qualified " + player + " rank=" + player.ranking + " " + player.score);

						// 優勝なら match に勝利数書き込み(squads win 未対応)
						// if (Core.myName.equals(player.name) && r.isFinal()) {
						if (r.myPlayerId == player.id && r.isFinal()) {
							Core.getCurrentMatch().winStreak = 1;
							List<Match> matches = Core.matches;
							if (matches.size() > 1)
								Core.getCurrentMatch().winStreak += matches.get(matches.size() - 2).winStreak;
						}

					} else {
						if (topObjectId == player.objectId) {
							topObjectId = 0; // 切断でも Handling unspawn が出るのでこれを無視して先頭ゴールのみ検出するため
							r.topFinish = null;
						}
						player.ranking = r.byId.size() - eliminatedCount;
						eliminatedCount += 1;
						System.out.println(player);
					}
					listener.roundUpdated();
				}
				break;
			}
			// score log
			// round over より後に出力されている。
			m = patternPlayerResult2.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				int finalScore = Integer.parseUnsignedInt(m.group(2));
				boolean isFinal = "True".equals(m.group(3));
				Player player = r.byId.get(playerId);
				System.out.println(
						"Result for " + playerId + " score=" + finalScore + " isFinal=" + isFinal + " " + player);
				if (player != null) {
					if (player.squadId > 0) { // 最後の squad 情報がバグで毎回出力されている
						player.finalScore = finalScore;
					}
				}
				break;
			}
			// round end
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains(
					"[GameSession] Changing state from Playing to GameOver")) {
				r.end = getTime(line);
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateQualificationScreen")
					|| line.contains(
							"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
				System.out.println("DETECT END GAME");
				r.fixed = true;
				// FIXME: teamId 相当が出力されないので誰がどのチームか判定できない。
				// 仕方ないので勝敗からチームを推測する。これだと２チーム戦しか対応できない。
				if (r.teamScore != null) {
					for (Player p : r.byId.values()) {
						if (r.teamScore[0] >= r.teamScore[1])
							p.teamId = Boolean.TRUE == p.qualified ? 0 : 1;
						else
							p.teamId = Boolean.TRUE == p.qualified ? 1 : 0;
					}
				}
				Core.getCurrentMatch().end = getTime(line);
				// 優勝画面に行ったらそのラウンドをファイナル扱いとする
				// final マークがつかないファイナルや、通常ステージで一人生き残り優勝のケースを補填するためだが
				// 通常ステージでゲーム終了時それをファイナルステージとみなすべきかはスコアリング上微妙ではある。
				if (line.contains(
						"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen"))
					r.isFinal = true;
				Core.updateStats();
				listener.roundDone();
				readState = ReadState.ROUND_DETECTING;
				break;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// 獲得 kudos 他はこの後に続く、決勝完了前に吐くこともあるのでステージ完了ではない。

				break;
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StatePrivateLobby with FGClient.StateMainMenu")
					|| line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMainMenu] Loading scene MainMenu")
					|| line.contains("[StateMatchmaking] Begin matchmaking")
					|| line.contains("Changing local player state to: SpectatingEliminated")
					|| line.contains("[GlobalGameStateClient] SwitchToDisconnectingState")) {
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
				readState = ReadState.SHOW_DETECTING;
				Core.getCurrentMatch().end = getTime(line);
				break;
			}
			break;
		}
	}
}

// UI
public class FallBallRecord extends JFrame implements FGReader.Listener {
	static int FONT_SIZE_BASE;
	static int FONT_SIZE_RANK;
	static int FONT_SIZE_DETAIL;

	static ServerSocketMutex mutex = new ServerSocketMutex(29879);
	static FallBallRecord frame;
	static FGReader reader;
	static String monospacedFontFamily = "MS Gothic";
	static String fontFamily = "Meiryo UI";

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		if (!mutex.tryLock()) {
			System.exit(0);
		}
		//	UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
		//	UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
		UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");

		Properties prop = new Properties();
		try (BufferedReader br = new BufferedReader(new FileReader("settings.ini"))) {
			prop.load(br);
		} catch (FileNotFoundException e) {
		}
		// default values
		String v = prop.getProperty("LANGUAGE");
		Core.LANG = v == null ? Locale.getDefault() : new Locale(v);
		Core.RES = ResourceBundle.getBundle("res", Core.LANG);

		v = prop.getProperty("FONT_SIZE_BASE");
		FONT_SIZE_BASE = v == null ? 12 : Integer.parseInt(v, 10);
		v = prop.getProperty("FONT_SIZE_RANK");
		FONT_SIZE_RANK = v == null ? 16 : Integer.parseInt(v, 10);
		v = prop.getProperty("FONT_SIZE_DETAIL");
		FONT_SIZE_DETAIL = v == null ? 16 : Integer.parseInt(v, 10);

		System.err.println("FONT_SIZE_BASE=" + FONT_SIZE_BASE);
		System.err.println("FONT_SIZE_RANK=" + FONT_SIZE_RANK);
		System.err.println("FONT_SIZE_DETAIL=" + FONT_SIZE_DETAIL);
		Rectangle winRect = new Rectangle(10, 10, 1280, 628);
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream("state.dat"))) {
			winRect = (Rectangle) in.readObject();
			Core.servers = (Map<String, Map<String, String>>) in.readObject();
		} catch (IOException ex) {
		}

		Core.load();

		frame = new FallBallRecord();
		frame.setResizable(true);
		frame.setBounds(winRect.x, winRect.y, winRect.width, winRect.height);
		frame.setTitle("Fall Ball Record");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	JLabel myStatLabel;
	JLabel pingLabel;
	JList<Match> matchSel;
	JList<Round> roundsSel;
	JTextPane roundDetailArea;
	JTextPane rankingArea;
	JComboBox<RoundFilter> filterSel;
	JComboBox<Integer> limitSel;
	JLabel rankingDescLabel;
	boolean ignoreSelEvent;

	static final int LINE1_Y = 10;
	static final int COL1_X = 10;

	FallBallRecord() {
		SpringLayout l = new SpringLayout();
		Container p = getContentPane();
		p.setLayout(l);

		JLabel label = new JLabel(Core.RES.getString("rankingLabel"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(200, 20);
		p.add(label);
		JLabel totalRankingLabel = label;

		filterSel = new JComboBox<RoundFilter>();
		filterSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, filterSel, 10, SpringLayout.EAST, label);
		l.putConstraint(SpringLayout.NORTH, filterSel, LINE1_Y, SpringLayout.NORTH, p);
		filterSel.setSize(95, 20);
		filterSel.addItem(new AllRoundFilter());
		filterSel.addItem(new CustomRoundFilter());
		filterSel.addItem(new NotCustomRoundFilter());
		filterSel.addItemListener(ev -> {
			Core.filter = (RoundFilter) filterSel.getSelectedItem();
			Core.updateStats();
			displayRanking();
		});
		p.add(filterSel);

		limitSel = new JComboBox<Integer>();
		limitSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, limitSel, 4, SpringLayout.EAST, filterSel);
		l.putConstraint(SpringLayout.NORTH, limitSel, LINE1_Y, SpringLayout.NORTH, p);
		limitSel.setSize(44, 20);
		limitSel.addItem(0);
		limitSel.addItem(10);
		limitSel.addItem(20);
		limitSel.addItem(50);
		limitSel.addItem(100);
		limitSel.addItem(500);
		limitSel.addItemListener(ev -> {
			Core.limit = (int) limitSel.getSelectedItem();
			Core.updateStats();
			displayRanking();
		});
		p.add(limitSel);
		label = new JLabel(Core.RES.getString("moreThanOneMatch"));
		label.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, label, 4, SpringLayout.EAST, limitSel);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(120, 20);
		p.add(label);

		final int COL2_X = COL1_X + FONT_SIZE_RANK * 25 + 10;
		final int COL3_X = COL2_X + 130;
		final int COL4_X = COL3_X + 160;

		label = new JLabel(Core.RES.getString("matchList"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel(Core.RES.getString("roundList"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel(Core.RES.getString("roundDetails"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);

		// under
		myStatLabel = new JLabel("");
		myStatLabel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_RANK));
		l.putConstraint(SpringLayout.WEST, myStatLabel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, myStatLabel, -4, SpringLayout.SOUTH, p);
		myStatLabel.setPreferredSize(new Dimension(FONT_SIZE_RANK * 16, FONT_SIZE_RANK + 10));
		p.add(myStatLabel);

		pingLabel = new JLabel("");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_RANK));
		l.putConstraint(SpringLayout.WEST, pingLabel, 40, SpringLayout.EAST, myStatLabel);
		l.putConstraint(SpringLayout.SOUTH, pingLabel, -4, SpringLayout.SOUTH, p);
		pingLabel.setPreferredSize(new Dimension(FONT_SIZE_RANK * 60, FONT_SIZE_RANK + 10));
		p.add(pingLabel);

		// styles
		StyledDocument rdoc = new DefaultStyledDocument();
		Style def = rdoc.getStyle(StyleContext.DEFAULT_STYLE);
		Style s = rdoc.addStyle("bold", def);
		StyleConstants.setBold(s, true);

		StyledDocument doc = new DefaultStyledDocument();
		def = doc.getStyle(StyleContext.DEFAULT_STYLE);
		s = doc.addStyle("bold", def);
		StyleConstants.setBold(s, true);
		/*
		s = doc.addStyle("underscore", def);
		StyleConstants.setUnderline(s, true);
		s = doc.addStyle("green", def);
		StyleConstants.setForeground(s, new Color(0x00cc00));
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("blue", def);
		StyleConstants.setForeground(s, Color.BLUE);
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("cyan", def);
		StyleConstants.setForeground(s, new Color(0x00cccc));
		s = doc.addStyle("magenta", def);
		StyleConstants.setForeground(s, new Color(0xcc00cc));
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("yellow", def);
		StyleConstants.setForeground(s, new Color(0xcccc00));
		s = doc.addStyle("red", def);
		StyleConstants.setForeground(s, Color.RED);
		//StyleConstants.setBold(s, true);
		*/

		JScrollPane scroller;

		rankingArea = new NoWrapJTextPane(rdoc);
		rankingArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_RANK));
		rankingArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(rankingArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, totalRankingLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -30, SpringLayout.SOUTH, p);
		scroller.setPreferredSize(new Dimension(FONT_SIZE_RANK * 25, 0));
		JScrollPane rankingAreaScroller = scroller;

		matchSel = new JList<Match>(new DefaultListModel<>());
		matchSel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE + 4));
		p.add(scroller = new JScrollPane(matchSel));
		l.putConstraint(SpringLayout.WEST, scroller, 10, SpringLayout.EAST, rankingAreaScroller);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, totalRankingLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -30, SpringLayout.SOUTH, p);
		scroller.setPreferredSize(new Dimension(120, 0));
		matchSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			matchSelected(getSelectedMatch());
		});

		roundsSel = new JList<Round>(new DefaultListModel<>());
		roundsSel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE + 4));
		p.add(scroller = new JScrollPane(roundsSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, totalRankingLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -30, SpringLayout.SOUTH, p);
		scroller.setPreferredSize(new Dimension(150, 0));
		roundsSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			roundSelected(getSelectedRound());
		});

		roundDetailArea = new NoWrapJTextPane(doc);
		roundDetailArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_DETAIL));
		roundDetailArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(roundDetailArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, totalRankingLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -60, SpringLayout.SOUTH, p);

		JButton removeMemberFromRoundButton = new JButton(Core.RES.getString("removeMemberFromRoundButton"));
		removeMemberFromRoundButton.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, removeMemberFromRoundButton, 0, SpringLayout.WEST, scroller);
		l.putConstraint(SpringLayout.NORTH, removeMemberFromRoundButton, 10, SpringLayout.SOUTH, scroller);
		removeMemberFromRoundButton.setPreferredSize(new Dimension(180, FONT_SIZE_BASE + 8));
		removeMemberFromRoundButton.addActionListener(ev -> removePlayerOnCurrentRound());
		p.add(removeMemberFromRoundButton);

		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				reader.stop();
				try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("state.dat"))) {
					out.writeObject(frame.getBounds());
					out.writeObject(Core.servers);
				} catch (IOException ex) {
					ex.printStackTrace();
				}
				Core.save();
				// log connected servers statistics
				Map<String, Integer> connected = new HashMap<String, Integer>();
				for (Match m : Core.matches) {
					Map<String, String> server = Core.servers.get(m.ip);
					if (server == null)
						continue;
					Integer count = connected.get(server.get("city"));
					if (count == null)
						count = 0;
					connected.put(server.get("city"), count + 1);
				}
				for (String city : connected.keySet()) {
					System.err.println(city + "\t" + connected.get(city));
				}
				for (Match m : Core.matches) {
					System.err.println("****** " + m.name);
					for (Round r : m.rounds) {
						System.err.println(r.name + "\t" + r.roundName2);
					}
				}
			}
		});

		Core.updateStats();
		displayRanking();
		// start log read
		reader = new FGReader(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player.log"), this);
		reader.start();
	}

	void updateMatches() {
		int prevSelectedIndex = matchSel.getSelectedIndex();
		DefaultListModel<Match> model = (DefaultListModel<Match>) matchSel.getModel();
		model.clear();
		model.addElement(new Match("ALL", null, null));
		synchronized (Core.listLock) {
			for (Match m : Core.matches) {
				model.addElement(m);
			}
			matchSel.setSelectedIndex(prevSelectedIndex <= 0 ? 0 : model.getSize() - 1);
			matchSel.ensureIndexIsVisible(matchSel.getSelectedIndex());
		}
		displayFooter();
	}

	void updateRounds() {
		DefaultListModel<Round> model = (DefaultListModel<Round>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			Match m = getSelectedMatch();
			for (Round r : m == null ? Core.rounds : m.rounds) {
				if (r.isFallBall()) {
					model.addElement(r);
				}
			}
			roundsSel.setSelectedIndex(model.size() - 1);
			roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
			displayRanking();
		}
	}

	void matchSelected(Match m) {
		DefaultListModel<Round> model = (DefaultListModel<Round>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			for (Round r : m == null ? Core.rounds : m.rounds) {
				if (r.isFallBall()) {
					model.addElement(r);
				}
			}
		}
		roundsSel.setSelectedIndex(model.size() - 1);
		roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
		displayFooter();
	}

	private void appendToRanking(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = rankingArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	private void appendToRoundDetail(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = roundDetailArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	void roundSelected(Round r) {
		refreshRoundDetail(r);
	}

	@Override
	public void showUpdated() {
		SwingUtilities.invokeLater(() -> {
			updateMatches();
		});
	}

	@Override
	public void roundStarted() {
		SwingUtilities.invokeLater(() -> {
			updateRounds();
		});
	}

	@Override
	public void roundUpdated() {
		SwingUtilities.invokeLater(() -> {
			if (Core.getCurrentRound() == getSelectedRound())
				refreshRoundDetail(getSelectedRound());
		});
	}

	@Override
	public void roundDone() {
		SwingUtilities.invokeLater(() -> {
			Core.updateStats();
			updateRounds();
		});
	}

	Match getSelectedMatch() {
		Match m = matchSel.getSelectedValue();
		if (m == null || "ALL".equals(m.name))
			return null;
		return m;
	}

	Round getSelectedRound() {
		return roundsSel.getSelectedValue();
	}

	private void removePlayerOnCurrentRound() {
		Round r = getSelectedRound();
		Core.rounds.remove(r);
		updateRounds();
		Core.updateStats();
		displayRanking();
	}

	void refreshRoundDetail(Round r) {
		roundDetailArea.setText("");
		if (r == null) {
			return;
		}
		if (r.topFinish != null) {
			long t = r.getTime(r.topFinish);
			appendToRoundDetail("TOP: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000), "bold");
		}
		if (r.myFinish != null && r.byId.get(r.myPlayerId) != null) {
			long t = r.getTime(r.myFinish);
			appendToRoundDetail("OWN: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000) + " #" + r.byId.get(r.myPlayerId).ranking, "bold");
		}
		if (r.end != null) {
			long t = r.getTime(r.end);
			appendToRoundDetail("END: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000), "bold");
		}
		if (r.teamScore != null) {
			appendToRoundDetail(Arrays.toString(r.teamScore), "bold");
		}
		if (r.isFinal()) {
			appendToRoundDetail("********** FINAL **********", "bold");
		}
		synchronized (Core.listLock) {
			appendToRoundDetail("rank  score  pt   name", null);
			for (Player p : r.byRank()) {
				StringBuilder buf = new StringBuilder();
				buf.append(p.qualified == null ? "　" : p.qualified ? "○" : "✕");
				buf.append(Core.pad(p.ranking)).append(" ");
				buf.append(Core.pad(p.score)).append("pt(").append(p.finalScore < 0 ? "  " : Core.pad(p.finalScore))
						.append(")").append(" ").append(p.partyId != 0 ? Core.pad(p.partyId) + " " : "   ");
				buf.append(r.myPlayerId == p.id ? "★" : "　").append(p);
				appendToRoundDetail(new String(buf), null);
			}
		}
		roundDetailArea.setCaretPosition(0);
	}

	void displayRanking() {
		rankingArea.setText("");
		myStatLabel.setText("");

		PlayerStat stat = Core.stat;
		appendToRanking("total: " + stat.totalWinCount + "/" + stat.totalParticipationCount + " ("
				+ Core.calRate(stat.totalWinCount, stat.totalParticipationCount) + "%)", "bold");
		int no = 0;
		for (ListIterator<Round> i = Core.rounds.listIterator(Core.rounds.size()); i.hasPrevious();) {
			Round r = i.previous();
			if (!r.isFallBall())
				continue;
			if (Core.filter != null && !Core.filter.isEnabled(r))
				continue;
			Player p = r.getMe();
			if (p == null)
				continue;
			no += 1;
			StringBuilder buf = new StringBuilder();
			buf.append(Core.pad(no));
			buf.append(" ").append(Core.pad(r.playerCount));
			buf.append(" ").append(Core.pad(r.getSubstancePlayerCount()));
			buf.append(" ").append(p.qualified == Boolean.TRUE ? "○" : "☓");
			buf.append(" ").append(Core.pad(r.getTeamScore(p.teamId))).append(":")
					.append(r.getTeamScore(1 - p.teamId));
			if (r.myFinish != null)
				buf.append(" ").append((double) r.getTime(r.myFinish) / 1000);
			appendToRanking(new String(buf), null);
			if (Core.limit > 0 && no >= Core.limit)
				break;
		}
		appendToRanking("ACHIVEMENTS", "bold");
		for (Achievement a : Core.achievements) {
			appendToRanking((a.isCompleted() ? "○" : "　") + a, null);
		}

		rankingArea.setCaretPosition(0);
		// footer
		myStatLabel.setText(Core.RES.getString("myStatLabel") + stat.winCount + " / " + stat.participationCount + " ("
				+ stat.getRate() + "%)");
	}

	static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);

	void displayFooter() {
		String text = "";
		Match m = getSelectedMatch();
		if (m == null)
			m = Core.getCurrentMatch();
		if (m == null)
			return;
		if (m.start != null) {
			text += "TIME:" + f.format(m.start) + (m.end == null ? "" : " - " + f.format(m.end));
		}
		if (m.winStreak > 0) {
			text += " WIN(" + m.winStreak + ")";
		}
		// server info
		text += " PING: " + m.pingMS + "ms " + m.ip;
		Map<String, String> server = Core.servers.get(m.ip);
		if (server != null)
			text += " " + server.get("country") + " " + server.get("regionName") + " " + server.get("city") + " "
					+ server.get("timezone");
		pingLabel.setText(text);
	}
}

class NoWrapJTextPane extends JTextPane {
	public NoWrapJTextPane() {
		super();
	}

	public NoWrapJTextPane(StyledDocument doc) {
		super(doc);
	}

	@Override
	public boolean getScrollableTracksViewportWidth() {
		// Only track viewport width when the viewport is wider than the preferred width
		return getUI().getPreferredSize(this).width <= getParent().getSize().width;
	};

	@Override
	public Dimension getPreferredSize() {
		// Avoid substituting the minimum width for the preferred width when the viewport is too narrow
		return getUI().getPreferredSize(this);
	};
}

class ServerSocketMutex {
	int port;
	ServerSocket ss;
	int count = 0;

	public ServerSocketMutex() {
		this(65123);
	}

	public ServerSocketMutex(int port) {
		this.port = port;
	}

	public synchronized boolean hasLock() {
		return ss != null;
	}

	public synchronized boolean tryLock() {
		if (ss != null) {
			count++;
			return true;
		}
		try {
			ss = new ServerSocket(port);
			return true;
		} catch (IOException e) {
		}
		return false;
	}

	/**
	 * ロックを獲得できるまでブロックします。
	 */
	public synchronized void lock() {
		while (true) {
			if (tryLock())
				return;
			try {
				wait(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public synchronized void unlock() {
		if (ss == null)
			return;
		if (count > 0) {
			count--;
			return;
		}
		try {
			ss.close();
		} catch (IOException e) {
		}
		ss = null;
	}
}
