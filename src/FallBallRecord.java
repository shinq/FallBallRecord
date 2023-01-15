import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
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
import java.io.InputStream;
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
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
import java.util.PropertyResourceBundle;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.ResourceBundle.Control;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SpringLayout;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
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
	int totalParticipationCount; // rate 分母。
	int totalWinCount; // rate 分子。
	int totalAchievementPoint;
	int totalDailyPoint;
	int todayDailyPoint;

	public double getRate() {
		return Core.calRate(winCount, participationCount);
	}

	public void reset() {
		totalParticipationCount = totalWinCount = participationCount = winCount = 0;
	}

	public int totalPoint() {
		return totalAchievementPoint + totalDailyPoint + todayDailyPoint;
	}

	public int getTitleIndex() {
		int p = totalPoint();
		int i = 0;
		for (; i < Core.titledPoints.length; i += 1) {
			if (p < Core.titledPoints[i]) {
				return i;
			}
		}
		return i;
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

class Round implements Comparable<Round> {
	final Match match;
	final String name;
	boolean isFinal;
	String roundName2; // より詳細な内部名
	boolean fixed; // ステージ完了まで読み込み済み
	int no; // 0 origin
	Date start;
	Date end;
	Date topFinish;
	Date myFinish;
	int myPlayerId;
	int[] teamScore;
	int playerCount;
	int playerCountAdd;
	boolean playerCountAddChanged;
	boolean disableMe;
	int qualifiedCount;
	Map<String, Player> byName = new HashMap<String, Player>();
	Map<Integer, Player> byId = new HashMap<Integer, Player>();

	public Round(String name, int no, Date id, boolean isFinal, Match match) {
		this.name = name;
		this.no = no;
		start = id;
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

	public boolean isEnabled() {
		Player p = getMe();
		return p != null && !disableMe;
	}

	public boolean isDate(int dayKey) {
		return dayKey == Core.toDayKey(start);
	}

	public boolean isWeek(int weekKey) {
		return weekKey == Core.toWeekKey(start);
	}

	public boolean isFallBall() {
		return fixed && "FallGuy_FallBall_5".equals(name);
	}

	public boolean isCustomFallBall() {
		return isFallBall() && "event_only_fall_ball_template".equals(match.name);
	}

	// デフォルトで20以下奇数時は -1 補正する。
	public int adjustPlayerCount() {
		return playerCount < 20 ? -playerCount % 2 : 0;
	}

	public int getSubstancePlayerCount() {
		return playerCount + playerCountAdd;
	}

	public int getSubstanceQualifiedCount() {
		return qualifiedCount * 2 > playerCount ? qualifiedCount + playerCountAdd : qualifiedCount;
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
		return start.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		Round o = (Round) obj;
		return start.equals(o.start);
	}

	@Override
	public int compareTo(Round o) {
		return start.compareTo(o.start);
	}

	public String toOverviewString() {
		Player p = getMe();
		StringBuilder buf = new StringBuilder();
		if (start == null)
			return getDef().getName();
		buf.append(Core.datef.format(start));
		if (p != null) {
			buf.append(" ").append(p.isQualified() ? "○" : "☓");
			if (p.isQualified())
				buf.append(Core.pad(getSubstanceQualifiedCount())).append("vs")
						.append(Core.pad(getSubstancePlayerCount() - getSubstanceQualifiedCount()));
			else
				buf.append(Core.pad(getSubstancePlayerCount() - getSubstanceQualifiedCount())).append("vs")
						.append(Core.pad(getSubstanceQualifiedCount()));
		}
		buf.append("(").append(Core.pad(playerCountAdd)).append(")");
		if (p != null)
			buf.append(" ").append(getTeamScore(p.teamId)).append(":")
					.append(getTeamScore(1 - p.teamId));
		if (myFinish != null)
			buf.append(" ").append(String.format("%.3f", (double) getTime(myFinish) / 1000)).append("s");
		buf.append(" ").append(match.ip);
		buf.append(" ").append(match.pingMS).append("ms");
		if (disableMe)
			buf.append(" ☓");
		return new String(buf);
	}

	@Override
	public String toString() {
		return toOverviewString();
		// return getDef().getName();
	}
}

// 一つのショー
class Match {
	final long session; // 起動日時ベース
	String name;
	final Date start; // id として使用
	final String ip;
	long pingMS;
	boolean fixed; // 完了まで読み込み済み
	Date end;
	int winStreak;
	List<Round> rounds = new ArrayList<Round>();

	public Match(long session, String name, Date start, String ip) {
		this.session = session;
		this.name = name;
		this.start = start;
		this.ip = ip;
	}

	public void addRound(Round r) {
		synchronized (Core.listLock) {
			int i = rounds.indexOf(r);
			if (i >= 0) {
				rounds.remove(i);
				rounds.add(i, r);
			} else
				rounds.add(r);
		}
	}

	public void finished(Date end) {
		this.end = end;

		// 優勝なら match に勝利数書き込み
		Round last = rounds.get(rounds.size() - 1);
		Player p = last.getMe();
		if (p != null && p.isQualified() && last.isFinal()) {
			winStreak = 1;
			List<Match> matches = Core.matches;
			if (matches.size() > 1)
				Core.currentMatch.winStreak += Core.matches.get(Core.matches.size() - 2).winStreak;
		}
	}

	public boolean isCurrentSession() {
		return session == Core.currentSession;
	}

	public boolean isDate(int dayKey) {
		return dayKey == Core.toDayKey(start);
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
// Core.rounds をもとに実績達成判定
// 一つの実績に複数の達成状態と付与ポイントを保持可能とする。
class GraphPanel extends JPanel {
	int currentValue;
	int[] threasholds;

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(200, 20);
	}

	@Override
	public void paint(Graphics g) {
		if (threasholds == null)
			return;
		int w = getWidth(), h = getHeight();
		int max = threasholds[threasholds.length - 1];
		g.clearRect(0, 0, w, h);
		g.setColor(Color.RED);
		g.fillRect(1, 1, (w - 2) * currentValue / max, h - 2);
		g.setColor(Color.BLUE);
		for (int x : threasholds) {
			if (x == max)
				break;
			int xx = (w - 2) * x / max + 1;
			g.drawLine(xx, 1, xx, h - 2);
		}
		g.setColor(Color.BLACK);
		g.drawRect(0, 0, w - 1, h - 1);
	}
}

class TitlePanel extends JLabel {
	GraphPanel graph = new GraphPanel();
	JLabel text = new JLabel();

	TitlePanel() {
		ImageIcon icon = new ImageIcon(getClass().getResource("b0.png"));
		setIcon(icon);
		setLayout(null);
		setSize(icon.getIconWidth(), icon.getIconHeight());
		add(graph);
		graph.setBounds(new Rectangle(68, 36, 100, 20));
		add(text);
		text.setHorizontalAlignment(SwingConstants.RIGHT);
		text.setForeground(Color.white);
		text.setBounds(new Rectangle(170, 36, 58, 20));
	}

	void update() {
		int i = Core.stat.getTitleIndex();
		int p = Core.stat.totalPoint();
		text.setText(p + "/" + Core.titledPoints[i]);
		graph.currentValue = p;
		graph.threasholds = new int[] { Core.titledPoints[i] };
		setIcon(new ImageIcon(getClass().getResource("b" + i + ".png")));
		repaint();
	}
}

abstract class Achievement {
	int currentValue;
	int myPoint;
	int[] threasholds;
	int[] points;
	JPanel panel = new JPanel(new BorderLayout());
	JLabel label = new JLabel();
	GraphPanel progressGraph = new GraphPanel();
	JLabel progressLabel = new JLabel();

	{
		panel.add(label, BorderLayout.NORTH);
		panel.add(progressGraph, BorderLayout.CENTER);
		panel.add(progressLabel, BorderLayout.EAST);
	}

	public void update(int dayKey, int weekKey) {
		calcCurrentValue(dayKey, weekKey);
		myPoint = 0;
		for (int i = 0; i < threasholds.length; i += 1)
			if (currentValue >= threasholds[i])
				myPoint += points[i];
		int max = threasholds[threasholds.length - 1];
		//if (currentValue > max) currentValue = max;
		progressGraph.currentValue = currentValue < max ? currentValue : max;
		progressGraph.threasholds = threasholds;
		progressGraph.repaint();
		label.setText(toString());
		progressLabel.setText(
				currentValue + "/" + max + " (" + Core.calRate(currentValue < max ? currentValue : max, max) + "%)");
		StringBuilder buf = new StringBuilder();
		buf.append("<html>");
		for (int i = 0; i < threasholds.length; i += 1) {
			if (currentValue >= threasholds[i])
				buf.append(threasholds[i] + "/" + threasholds[i] + " = " + points[i] + "pt GET!<br>");
			else
				buf.append(currentValue + "/" + threasholds[i] + " = " + points[i] + "pt<br>");
		}
		buf.append("</html>");
		panel.setToolTipText(new String(buf));
	}

	public abstract void calcCurrentValue(int dayKey, int weekKey);

	public abstract String getName();

	@Override
	public String toString() {
		return getName() + "(" + points[points.length - 1] + ")";
	}
}

class RoundCountAchievement extends Achievement {
	public RoundCountAchievement(int[] threasholds, int[] points) {
		this.threasholds = threasholds;
		this.points = points;
	}

	@Override
	public void calcCurrentValue(int dayKey, int weekKey) {
		currentValue = Core.filter(
				r -> r.isFallBall() && r.isCustomFallBall() && r.isEnabled()
						&& (dayKey == 0 || r.isDate(dayKey))
						&& (weekKey == 0 || r.isWeek(weekKey)))
				.size();
	}

	@Override
	public String getName() {
		return "Custom Rounds played";
	}
}

class WinCountAchievement extends Achievement {
	boolean overtimeOnly;
	int playerCount;
	int thresholdScoreDiff;

	public WinCountAchievement(int[] threasholds, int[] points, boolean overtimeOnly, int playerCount, int scoreDiff) {
		this.threasholds = threasholds;
		this.points = points;
		this.overtimeOnly = overtimeOnly;
		this.playerCount = playerCount;
		this.thresholdScoreDiff = scoreDiff;
	}

	@Override
	public void calcCurrentValue(int dayKey, int weekKey) {
		currentValue = Core
				.filter(r -> {
					if (playerCount > 8 && r.getSubstanceQualifiedCount() < playerCount)
						return false;
					if (playerCount > 0 && playerCount < 9 && r.getSubstanceQualifiedCount() != playerCount)
						return false;
					if (r.teamScore != null) {
						int scoreDiff = Math.abs(r.teamScore[0] - r.teamScore[1]);
						if (thresholdScoreDiff == 1 && scoreDiff != 1)
							return false;
						if (thresholdScoreDiff > 1 && scoreDiff < thresholdScoreDiff)
							return false;
					}
					return r.isFallBall() && r.isCustomFallBall() && r.isEnabled() && r.isQualified()
							&& (!overtimeOnly || (r.myFinish != null && r.getTime(r.myFinish) > 121000))
							&& (dayKey == 0 || r.isDate(dayKey))
							&& (weekKey == 0 || r.isWeek(weekKey));
				})
				.size();
	}

	@Override
	public String getName() {
		return "Wins" + (overtimeOnly ? " at overtime round" : "")
				+ (playerCount > 0 ? " on " + playerCount + "vs" + playerCount : "")
				+ (thresholdScoreDiff > 0 ? " over " + thresholdScoreDiff + " scores" : "");
	}
}

class StreakAchievement extends Achievement {
	int targetStreak;

	public StreakAchievement(int[] threasholds, int[] points, int streak) {
		this.threasholds = threasholds;
		this.points = points;
		this.targetStreak = streak;
	}

	@Override
	public void calcCurrentValue(int dayKey, int weekKey) {
		currentValue = 0;
		int streak = 0;
		for (Round r : Core.filter(
				r -> r.isFallBall() && r.isCustomFallBall() && r.isEnabled()
						&& (dayKey == 0 || r.isDate(dayKey)))) {
			if (r.isQualified()) {
				streak += 1;
				if (targetStreak == streak)
					currentValue += 1;
			} else
				streak = 0;
		}
	}

	@Override
	public String getName() {
		return targetStreak + " wins streak";
	}
}

class RateAchievement extends Achievement {
	double targetRate;
	int limit;

	public RateAchievement(int[] threasholds, int[] points, double rate, int limit) {
		this.threasholds = threasholds;
		this.points = points;
		this.targetRate = rate;
		this.limit = limit;
	}

	@Override
	public void calcCurrentValue(int dayKey, int weekKey) {
		currentValue = 0;
		int winCount = 0;
		List<Round> filtered = Core.filter(r -> r.isFallBall() && r.isCustomFallBall() && r.isEnabled());
		if (filtered.size() < limit)
			return;
		List<Round> targetRounds = new ArrayList<>();
		for (Round r : filtered) {
			targetRounds.add(r);
			winCount += r.isQualified() ? 1 : 0;
			if (targetRounds.size() > limit) {
				Round o = targetRounds.remove(0);
				winCount -= o.isQualified() ? 1 : 0;
			}
			double rate = Core.calRate(winCount, targetRounds.size());
			if (targetRounds.size() >= limit && rate >= targetRate)
				currentValue += 1;
		}
	}

	@Override
	public String getName() {
		return targetRate + "% wins in " + limit + " rounds";
	}
}

///////////////////////////////////////////////////////////////////////////////////////////////////////
interface RoundFilter {
	boolean isEnabled(Round r);
}

class AllRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.isFallBall() && r.getMe() != null;
	}

	@Override
	public String toString() {
		return "ALL";
	}
}

class CurrentSessionRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.isFallBall() && r.getMe() != null && r.match.isCurrentSession();
	}

	@Override
	public String toString() {
		return "CurrentSesionOnly";
	}
}

class CustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.isFallBall() && r.getMe() != null && r.isCustomFallBall();
	}

	@Override
	public String toString() {
		return "CustomOnly";
	}
}

class NotCustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.isFallBall() && r.getMe() != null && !r.isCustomFallBall();
	}

	@Override
	public String toString() {
		return "Not CustomOnly";
	}
}

class FewAllyCustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		if (!r.isCustomFallBall())
			return false;
		if (r.isQualified())
			return r.getSubstanceQualifiedCount() * 2 < r.getSubstancePlayerCount();
		else
			return r.getSubstanceQualifiedCount() * 2 > r.getSubstancePlayerCount();
	}

	@Override
	public String toString() {
		return "Few ally(Custom)";
	}
}

class ManyAllyCustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		if (!r.isCustomFallBall())
			return false;
		if (r.isQualified())
			return r.getSubstanceQualifiedCount() * 2 > r.getSubstancePlayerCount();
		else
			return r.getSubstanceQualifiedCount() * 2 < r.getSubstancePlayerCount();
	}

	@Override
	public String toString() {
		return "Many ally(Custom)";
	}
}

class PlayerCountRoundFilter implements RoundFilter {
	int count;

	public PlayerCountRoundFilter(int count) {
		this.count = count;
	}

	@Override
	public boolean isEnabled(Round r) {
		if (!r.isCustomFallBall())
			return false;
		if (count > 7)
			return r.getSubstanceQualifiedCount() >= count;
		return r.getSubstanceQualifiedCount() == count;
	}

	@Override
	public String toString() {
		return count + "vs" + count + (count > 7 ? "over" : "") + "(Custom)";
	}
}

class Core {
	static Locale LANG;
	static ResourceBundle RES;
	static Object listLock = new Object();
	static boolean started = false;

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

	public static int toDayKey(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		return c.get(Calendar.YEAR) * 10000 + c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH);
	}

	public static int toWeekKey(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		return c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR);
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
	public static RoundFilter filter = new AllRoundFilter();
	public static int limit = 0;
	public static long currentSession;
	public static int currentYear;
	public static int currentMonth;
	public static int currentUTCDate;
	public static String currentServerIp;
	public static final List<Match> matches = new ArrayList<>();
	public static final List<Round> rounds = new ArrayList<>();
	public static Match currentMatch;
	public static Round currentRound;
	public static List<Round> filtered;
	public static Map<String, Map<String, String>> servers = new HashMap<>();
	public static final PlayerStat stat = new PlayerStat();
	public static final List<Achievement> achievements = new ArrayList<>();
	public static final List<Achievement> dailyChallenges = new ArrayList<>();
	public static final List<Achievement> weeklyChallenges = new ArrayList<>();

	static final SimpleDateFormat datef = new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN);
	static final DateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS");
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 5 }, 55, 30));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 5 }, 60, 30));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 10 }, 65, 30));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 20 }, 70, 30));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 20 }, 75, 30));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 5 }, 55, 50));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 10 }, 60, 50));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 20 }, 65, 50));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 50 }, 70, 50));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 10 }, 55, 100));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 20 }, 60, 100));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 50 }, 65, 100));
		achievements.add(new RateAchievement(new int[] { 1 }, new int[] { 100 }, 70, 100));
		// rounds
		achievements.add(new RoundCountAchievement(new int[] { 500, 1000, 2500 },
				new int[] { 5, 5, 5 }));
		achievements.add(new RoundCountAchievement(
				new int[] { 5000, 7500, 10000, 12500, 15000, 17500, 20000, 22500, 25000, 27500, 30000 },
				new int[] { 5, 8, 10, 13, 15, 18, 20, 23, 25, 28, 30 }));
		// wins
		achievements.add(new WinCountAchievement(new int[] { 500, 1000, 2500 },
				new int[] { 5, 5, 5 }, false, 0, 0));
		achievements.add(new WinCountAchievement(new int[] { 5000, 7500, 10000, 12500, 15000, 17500, 20000 },
				new int[] { 10, 15, 20, 25, 30, 35, 40 }, false, 0, 0));

		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500 },
				new int[] { 5, 5, 10 }, true, 0, 0));
		achievements.add(new WinCountAchievement(new int[] { 750, 1000 },
				new int[] { 15, 20 }, true, 0, 0));

		// playerCount
		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500, 1000, 1500, 2000 },
				new int[] { 5, 5, 5, 10, 15, 20 }, false, 4, 0));
		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500, 1000, 1500, 2000 },
				new int[] { 5, 5, 5, 10, 15, 20 }, false, 5, 0));
		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500, 1000, 1500, 2000 },
				new int[] { 5, 5, 5, 10, 15, 20 }, false, 9, 0));
		// score
		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500, 750, 1000 },
				new int[] { 5, 5, 10, 15, 20 }, false, 0, 3));
		achievements.add(new WinCountAchievement(new int[] { 50, 100, 500, 750, 1000 },
				new int[] { 5, 5, 10, 15, 20 }, false, 0, 5));

		// streaks
		achievements.add(new StreakAchievement(new int[] { 10, 20, 30 },
				new int[] { 10, 15, 20 }, 3));
		achievements.add(new StreakAchievement(new int[] { 10, 20, 30 },
				new int[] { 10, 15, 20 }, 5));

		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 8 }, 4));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 10 }, 5));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 12 }, 6));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 14 }, 7));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 16 }, 8));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 18 }, 9));
		achievements.add(new StreakAchievement(new int[] { 1 }, new int[] { 20 }, 10));

		dailyChallenges.add(new RoundCountAchievement(new int[] { 10 }, new int[] { 1 }));
		dailyChallenges.add(new RoundCountAchievement(new int[] { 20 }, new int[] { 1 }));
		dailyChallenges.add(new WinCountAchievement(new int[] { 5 }, new int[] { 1 }, false, 0, 0));
		dailyChallenges.add(new WinCountAchievement(new int[] { 10 }, new int[] { 2 }, false, 0, 0));
		dailyChallenges.add(new WinCountAchievement(new int[] { 1 }, new int[] { 1 }, false, 4, 0));
		dailyChallenges.add(new WinCountAchievement(new int[] { 1 }, new int[] { 1 }, false, 5, 0));
		dailyChallenges.add(new WinCountAchievement(new int[] { 1 }, new int[] { 2 }, true, 0, 0));
		dailyChallenges.add(new WinCountAchievement(new int[] { 1 }, new int[] { 1 }, false, 0, 1));
		dailyChallenges.add(new WinCountAchievement(new int[] { 1 }, new int[] { 2 }, false, 0, 3));
		dailyChallenges.add(new StreakAchievement(new int[] { 1 }, new int[] { 1 }, 2));
		dailyChallenges.add(new StreakAchievement(new int[] { 1 }, new int[] { 2 }, 3));

		weeklyChallenges.add(new RoundCountAchievement(new int[] { 100 }, new int[] { 5 }));
		weeklyChallenges.add(new WinCountAchievement(new int[] { 75 }, new int[] { 10 }, false, 0, 0));
	}
	static final int[] titledPoints = new int[] { 100, 1000, 3000, 7000, 10000 };

	public static void load() {
		rounds.clear();
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream("stats.tsv"), StandardCharsets.UTF_8))) {
			String line;
			Match m = null;
			while ((line = in.readLine()) != null) {
				String[] d = line.split("\t");
				if (d.length < 6)
					continue;

				if ("M".equals(d[0])) {
					Date matchStart = f.parse(d[2]);
					m = new Match(Long.parseLong(d[1]), d[3], matchStart, d[4]);
					m.pingMS = Integer.parseInt(d[5]);
					m.winStreak = Integer.parseInt(d[6]);
					addMatch(m);
					continue;
				}
				if (!"r".equals(d[0]) || d.length < 13)
					continue;
				Round r = new Round(d[3], Integer.parseInt(d[2]), f.parse(d[1]), "true".equals(d[5]), m);
				r.roundName2 = d[4];
				r.fixed = true;

				r.playerCount = Integer.parseInt(d[6]);
				r.qualifiedCount = Integer.parseInt(d[7]);
				if (d[8].length() > 0)
					r.myFinish = r.end = new Date(r.start.getTime() + Long.parseUnsignedLong(d[8]));
				Player p = new Player(0);
				p.name = "YOU";
				p.qualified = "true".equals(d[9]);
				r.disableMe = "true".equals(d[10]);
				r.playerCountAdd = Integer.parseInt(d[11]);
				r.playerCountAddChanged = r.playerCountAdd != r.adjustPlayerCount();
				p.teamId = Integer.parseInt(d[12]);
				if (d.length > 13)
					r.teamScore = Core.intArrayFromString(d[13]);
				r.add(p);
				addRound(r);
				r.playerCount = Integer.parseInt(d[6]); // reset
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public static void save() {
		try {
			Files.copy(Paths.get("stats.tsv"), Paths.get("stats_prev.tsv"));
		} catch (IOException e) {
		}
		try (PrintWriter out = new PrintWriter(
				new OutputStreamWriter(new FileOutputStream("stats.tsv"), StandardCharsets.UTF_8),
				false)) {
			out.println(
					"Type\tStart\tNo\tName\tName2\tFinal\tPlayers\tQualifiedCount\tTime\tQualified\tDisabled\tplayerCoundAdd\tTeam\tTeamScore");
			Match currentMatch = null;
			Collections.sort(rounds);
			for (Round r : rounds) {
				if (!r.isFallBall())
					continue;
				Player p = r.getMe();
				if (p == null)
					continue;
				if (currentMatch == null || !currentMatch.equals(r.match)) {
					currentMatch = r.match;
					// write match line
					out.print("M"); // 0
					out.print("\t");
					out.print(currentMatch.session); // 1
					out.print("\t");
					out.print(f.format(currentMatch.start)); // 2
					out.print("\t");
					out.print(currentMatch.name); // 3
					out.print("\t");
					out.print(currentMatch.ip); // 4
					out.print("\t");
					out.print(currentMatch.pingMS); // 5
					out.print("\t");
					out.print(currentMatch.winStreak); // 6
					out.println();
				}
				out.print("r"); // 0
				out.print("\t");
				out.print(f.format(r.start)); // 1
				out.print("\t");
				out.print(r.no); // 2
				out.print("\t");
				out.print(r.name); // 3
				out.print("\t");
				out.print(r.roundName2); // 4
				out.print("\t");
				out.print(r.isFinal()); // 5
				out.print("\t");
				out.print(r.playerCount); // 6
				out.print("\t");
				out.print(r.qualifiedCount); // 7
				out.print("\t");
				if (r.myFinish != null)
					out.print(r.getTime(r.myFinish)); // 8
				else if (r.end != null)
					out.print(r.getTime(r.end)); // 8

				out.print("\t");
				out.print(p.isQualified()); // 9
				out.print("\t");
				out.print(r.disableMe); // 10
				out.print("\t");
				out.print(r.playerCountAdd); // 11
				out.print("\t");
				out.print(p.teamId); // 12
				out.print("\t");
				if (r.teamScore != null)
					out.print(Arrays.toString(r.teamScore)); // 13
				out.println();
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	// 2000-01-01 の時刻にする
	static Date normalizedDate(Date d) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		c.setTime(d);
		c.set(Calendar.YEAR, 2000);
		c.set(Calendar.MONTH, 0);
		c.set(Calendar.DAY_OF_MONTH, 1);
		return c.getTime();
	}

	public static void addMatch(Match m) {
		currentMatch = m;
		synchronized (listLock) {
			// 既にあれば差し替え
			int i = matches.indexOf(m);
			if (i >= 0) {
				matches.remove(i);
				matches.add(i, m);
			} else {
				// 日付が異なり時刻が一致するマッチがあった場合日付認識ミスの重複なので古い方を不参加マークにする。
				Date normalizedDate = normalizedDate(m.start);
				for (Match o : matches) {
					if (normalizedDate.equals(normalizedDate(o.start))) {
						for (Round r : o.rounds)
							rounds.remove(r);
						matches.remove(o);
						break;
					}
				}
				matches.add(m);
			}
			// 直前のマッチのラウンド０だったら除去
			if (matches.size() > 2 && matches.get(matches.size() - 2).rounds.size() == 0)
				matches.remove(matches.size() - 2);
		}
	}

	public static void addRound(Round r) {
		currentRound = r;
		synchronized (listLock) {
			r.match.addRound(r);
			int i = rounds.indexOf(r);
			if (i >= 0) {
				Round o = rounds.remove(i);
				r.playerCountAdd = o.playerCountAdd;
				r.playerCountAddChanged = o.playerCountAddChanged;
				r.disableMe = o.disableMe;
				rounds.add(i, r);
			} else
				rounds.add(r);
		}
	}

	// 新しい順にする
	public static List<Round> filter(RoundFilter f) {
		return filter(f, 0, false);
	}

	public static List<Round> filter(RoundFilter f, int limit, boolean cacheUpdate) {
		List<Round> result = new ArrayList<>();
		int c = 0;
		synchronized (listLock) {
			for (ListIterator<Round> i = rounds.listIterator(rounds.size()); i.hasPrevious();) {
				Round r = i.previous();
				if (f != null && !f.isEnabled(r))
					continue;
				result.add(r);
				c += 1;
				if (limit > 0 && c >= limit)
					break;
			}
		}
		if (cacheUpdate)
			filtered = result;
		return result;
	}

	public static void updateStats() {
		if (!started)
			return;
		synchronized (listLock) {
			stat.reset();
			for (Round r : filter(filter, limit, true)) {
				if (!r.fixed || !r.isEnabled() || r.getSubstanceQualifiedCount() == 0)
					continue;

				// このラウンドの参加者の結果を反映
				for (Player p : r.byId.values()) {
					if (!"YOU".equals(p.name))
						continue;
					if (r.match.isCurrentSession()) {
						stat.participationCount += 1; // 参加 round 数
						stat.winCount += p.isQualified() ? 1 : 0;
					}
					stat.totalParticipationCount += 1; // 参加 round 数
					stat.totalWinCount += p.isQualified() ? 1 : 0;
				}
			}
		}
	}

	public static void updateAchivements() {
		if (!started)
			return;
		SwingUtilities.invokeLater(() -> {
			synchronized (Core.listLock) {
				stat.totalAchievementPoint = 0;
				for (Achievement a : achievements) {
					a.update(0, 0);
					stat.totalAchievementPoint += a.myPoint;
				}

				stat.totalDailyPoint = 0;
				int currentWeekKey = 0;
				int currentDayKey = 0;
				for (Round r : Core.rounds) {
					int dayKey = Core.toDayKey(r.start);
					int weekKey = Core.toWeekKey(r.start);
					if (currentDayKey == 0 || currentDayKey != dayKey) {
						currentDayKey = dayKey;
						for (Achievement a : Core.getChallenges(dayKey)) {
							a.update(dayKey, 0);
							stat.totalDailyPoint += a.myPoint;
						}
					}
					if (currentWeekKey == 0 || currentWeekKey != weekKey) {
						currentWeekKey = weekKey;
						for (Achievement a : Core.weeklyChallenges) {
							a.update(0, weekKey);
							stat.totalDailyPoint += a.myPoint;
						}
					}
				}
				stat.todayDailyPoint = 0;
				/*
				for (Achievement a : Core.getChallenges(dayKey)) {
					a.update(dayKey);
					stat.todayDailyPoint += a.currentValue;
				}
				*/
			}
		});
	}

	public static List<Achievement> getChallenges(int dayKey) {
		Random random = new Random(dayKey);

		List<Achievement> result = new ArrayList<>(dailyChallenges); // copy
		Collections.shuffle(result, random);
		while (result.size() > 3)
			result.remove(result.size() - 1);
		return result;
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

	static Pattern patternDateDetect = Pattern
			.compile("(\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d)[^ ]+ LogEOS\\(Info\\)");
	static Pattern patternLaunch = Pattern
			.compile("\\[FGClient.GlobalInitialisation\\] Active Scene is 'Init'");
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

	static DateFormat date8601Local = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	static DateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	static Date getTime(String line) {
		try {
			Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			Calendar parsed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			parsed.setTime(f.parse(line.substring(0, 12)));
			c.set(Calendar.YEAR, Core.currentYear);
			c.set(Calendar.MONTH, Core.currentMonth);
			c.set(Calendar.DAY_OF_MONTH, Core.currentUTCDate);
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
		Round r = Core.currentRound;
		Matcher m = patternLaunch.matcher(line);
		if (m.find()) {
			Core.currentSession = getTime(line).getTime();
			return;
		}
		m = patternDateDetect.matcher(line);
		if (m.find()) {
			try {
				Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				c.setTime(date8601Local.parse(m.group(1)));
				Core.currentYear = c.get(Calendar.YEAR);
				Core.currentMonth = c.get(Calendar.MONTH);
				Core.currentUTCDate = c.get(Calendar.DAY_OF_MONTH);
			} catch (ParseException e) {
				//e.printStackTrace();
			}
			return;
		}
		/*
		if (line.contains("[UserInfo] Player Name:")) {
			String[] sp = line.split("Player Name: ", 2);
			Core.myNameFull = sp[1];
		}
		*/
		m = patternServer.matcher(line);
		if (m.find()) {
			String showName = "_";
			String ip = m.group(1);
			Match match = new Match(Core.currentSession, showName, getTime(line), ip);
			Core.addMatch(match);
			System.out.println("DETECT SHOW STARTING");
			readState = ReadState.ROUND_DETECTING;

			if (match.pingMS == 0) {
				Core.currentServerIp = ip;
				// ping check
				try {
					InetAddress address = InetAddress.getByName(ip);
					long now = System.currentTimeMillis();
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
			listener.showUpdated();
			return;
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
				Core.currentMatch.name = showName;
				listener.showUpdated();
				return;
			}
			if (line.contains("isFinalRound=")) {
				isFinal = line.contains("isFinalRound=True");
				return;
			}
			m = patternRoundName.matcher(line);
			if (m.find()) {
				String roundName = m.group(1);
				//long frame = Long.parseUnsignedLong(m.group(2)); // FIXME: round id のほうが適切
				Core.addRound(new Round(roundName, Core.currentMatch.rounds.size(), getTime(line), isFinal,
						Core.currentMatch));
				r = Core.currentRound;
				System.out.println("DETECT STARTING " + roundName);
				//readState = ReadState.MEMBER_DETECTING;
				return;
			}
			m = patternLoadedRound.matcher(line);
			if (m.find()) {
				String roundName2 = m.group(1);
				r.roundName2 = roundName2;
				System.out.println("DETECT STARTING " + roundName2);
				readState = ReadState.MEMBER_DETECTING;
				return;
			}
			return;
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
				return;
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
				return;
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
				return;
			}
			*/
			if (line.contains("[StateGameLoading] Starting the game.")) {
				listener.roundStarted();
				return;
			}
			if (line.contains("[GameSession] Changing state from Countdown to Playing")) {
				// start を書き換える前のエントリを除去
				synchronized (Core.listLock) {
					Core.rounds.remove(r);
					Core.currentMatch.rounds.remove(r);
				}
				r.start = getTime(line);
				Core.addRound(r); // 再add
				topObjectId = 0;
				listener.roundStarted();
				qualifiedCount = eliminatedCount = 0; // reset
				readState = ReadState.RESULT_DETECTING;
				if (r.getDef().type == RoundType.SURVIVAL) {
					survivalScoreTimer = new Timer();
					survivalScoreTimer.scheduleAtFixedRate(new TimerTask() {
						@Override
						public void run() {
							for (Player p : Core.currentRound.byId.values()) {
								if (p.qualified == null)
									p.score += 1;
							}
							listener.roundUpdated();
						}
					}, 1000, 1000);
				}
				return;
			}
			if (line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMatchmaking] Begin matchmaking")) {
				System.out.println("DETECT BACK TO LOBBY");
				Core.rounds.remove(Core.rounds.size() - 1); // delete current round
				readState = ReadState.SHOW_DETECTING;
				return;
			}
			return;
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
				return;
			}
			m = patternTeamScoreUpdated.matcher(line);
			if (m.find()) {
				int teamCount = r.getDef().teamCount;
				if (teamCount < 2)
					return;
				int teamId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				if (r.teamScore == null)
					r.teamScore = new int[teamCount];
				r.teamScore[teamId] = score;
				listener.roundUpdated();
				return;
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
				return;
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
						r.qualifiedCount += 1;
						player.ranking = qualifiedCount;
						System.out.println("Qualified " + player + " rank=" + player.ranking + " " + player.score);
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
				return;
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
				return;
			}
			// round end
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains("[GameSession] Changing state from Playing to GameOver")) {
				r.end = getTime(line);
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
				return;
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
				// fallball 専用処理
				if (!r.playerCountAddChanged)
					r.playerCountAdd = r.adjustPlayerCount(); // デフォルトで20以下奇数時は -1 補正する。
				Core.currentMatch.end = getTime(line);
				// 優勝画面に行ったらそのラウンドをファイナル扱いとする
				// final マークがつかないファイナルや、通常ステージで一人生き残り優勝のケースを補填するためだが
				// 通常ステージでゲーム終了時それをファイナルステージとみなすべきかはスコアリング上微妙ではある。
				if (line.contains(
						"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
					r.isFinal = true;
					Core.currentMatch.finished(getTime(line));
				}
				listener.roundDone();
				readState = ReadState.ROUND_DETECTING;
				return;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// 獲得 kudos 他はこの後に続く、決勝完了前に吐くこともあるのでステージ完了ではない。
				return;
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
				Core.currentMatch.finished(getTime(line));
				return;
			}
			return;
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
		Core.RES = ResourceBundle.getBundle("res", Core.LANG, new UTF8Control());

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

		frame.readLog();

		frame.setVisible(true);
		Core.started = true;
		// ad-hoc show initial stats
		// ラウンド終了検出で更新されるがそれだけだと起動時ログがないときの初期表示がされないのでとりあえず
		Core.updateStats();
		Core.updateAchivements();
		frame.updateRounds();
		frame.displayStats();
		frame.achievementPanel.updateDaily();

		reader.start();
	}

	JLabel pingLabel;
	JTextPane statsArea;
	TitlePanel titlePanel;
	AchievementPanel achievementPanel;
	JList<Round> roundsSel;
	JComboBox<RoundFilter> filterSel;
	JComboBox<Integer> limitSel;
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
		JLabel statsLabel = label;

		final int COL2_X = COL1_X + FONT_SIZE_RANK * 18 + 10;
		final int COL3_X = COL2_X + 340;

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

		label = new JLabel("v0.3.5");
		label.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.EAST, label, -8, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.SOUTH, label, -8, SpringLayout.SOUTH, p);
		p.add(label);

		// under
		pingLabel = new JLabel("");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_RANK));
		l.putConstraint(SpringLayout.WEST, pingLabel, 10, SpringLayout.WEST, p);
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

		titlePanel = new TitlePanel();
		p.add(titlePanel);
		l.putConstraint(SpringLayout.WEST, titlePanel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, titlePanel, 8, SpringLayout.SOUTH, statsLabel);

		statsArea = new NoWrapJTextPane(rdoc);
		statsArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_RANK));
		statsArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(statsArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, COL2_X - 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, titlePanel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -100, SpringLayout.SOUTH, p);

		filterSel = new JComboBox<RoundFilter>();
		filterSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, filterSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, filterSel, -90, SpringLayout.SOUTH, p);
		filterSel.setSize(95, 20);
		filterSel.addItem(new AllRoundFilter());
		filterSel.addItem(new CurrentSessionRoundFilter());
		filterSel.addItem(new CustomRoundFilter());
		filterSel.addItem(new NotCustomRoundFilter());
		filterSel.addItem(new PlayerCountRoundFilter(4));
		filterSel.addItem(new PlayerCountRoundFilter(5));
		filterSel.addItem(new PlayerCountRoundFilter(6));
		filterSel.addItem(new PlayerCountRoundFilter(7));
		filterSel.addItem(new PlayerCountRoundFilter(8));
		filterSel.addItem(new FewAllyCustomRoundFilter());
		filterSel.addItem(new ManyAllyCustomRoundFilter());
		Core.filter = (RoundFilter) filterSel.getSelectedItem();
		filterSel.addItemListener(ev -> {
			Core.filter = (RoundFilter) filterSel.getSelectedItem();
			Core.updateStats();
			updateRounds();
		});
		p.add(filterSel);

		limitSel = new JComboBox<Integer>();
		limitSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, limitSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, limitSel, -60, SpringLayout.SOUTH, p);
		limitSel.setSize(44, 20);
		limitSel.addItem(0);
		limitSel.addItem(10);
		limitSel.addItem(20);
		limitSel.addItem(50);
		limitSel.addItem(100);
		limitSel.addItem(500);
		Core.limit = 100;
		limitSel.setSelectedItem(100);
		limitSel.addItemListener(ev -> {
			Core.limit = (int) limitSel.getSelectedItem();
			Core.updateStats();
			updateRounds();
		});
		p.add(limitSel);
		label = new JLabel(Core.RES.getString("moreThanOneMatch"));
		label.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, label, 6, SpringLayout.EAST, limitSel);
		l.putConstraint(SpringLayout.NORTH, label, 2, SpringLayout.NORTH, limitSel);
		label.setSize(120, 20);
		p.add(label);

		achievementPanel = new AchievementPanel();
		p.add(scroller = new JScrollPane(achievementPanel));
		l.putConstraint(SpringLayout.WEST, scroller, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, COL3_X - 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -30, SpringLayout.SOUTH, p);
		scroller.setPreferredSize(new Dimension(FONT_SIZE_RANK * 25, 0));

		roundsSel = new JList<Round>(new DefaultListModel<>());
		roundsSel.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_BASE + 4));
		p.add(scroller = new JScrollPane(roundsSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -60, SpringLayout.SOUTH, p);
		scroller.setPreferredSize(new Dimension(150, 0));
		roundsSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			roundSelected(getSelectedRound());
		});

		/*
		roundDetailArea = new NoWrapJTextPane(doc);
		roundDetailArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_DETAIL));
		roundDetailArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(roundDetailArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, totalstatsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -60, SpringLayout.SOUTH, p);
		*/

		JButton removeMemberFromRoundButton = new JButton(Core.RES.getString("removeMemberFromRoundButton"));
		removeMemberFromRoundButton.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, removeMemberFromRoundButton, 0, SpringLayout.WEST, scroller);
		l.putConstraint(SpringLayout.NORTH, removeMemberFromRoundButton, 10, SpringLayout.SOUTH, scroller);
		removeMemberFromRoundButton.setPreferredSize(new Dimension(180, FONT_SIZE_BASE + 8));
		removeMemberFromRoundButton.addActionListener(ev -> removePlayerOnCurrentRound());
		p.add(removeMemberFromRoundButton);

		JButton adjustPlayerCountButton = new JButton(Core.RES.getString("adjustPlayerCountButton"));
		adjustPlayerCountButton.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, adjustPlayerCountButton, 10, SpringLayout.EAST, removeMemberFromRoundButton);
		l.putConstraint(SpringLayout.NORTH, adjustPlayerCountButton, 10, SpringLayout.SOUTH, scroller);
		adjustPlayerCountButton.setPreferredSize(new Dimension(180, FONT_SIZE_BASE + 8));
		adjustPlayerCountButton.addActionListener(ev -> adjustPlayerCountOnCurrentRound());
		p.add(adjustPlayerCountButton);

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
	}

	public void readLog() {
		// start log read
		reader = new FGReader(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player.log"), this);
		readLogInternal(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player-prev.log"));
	}

	void readLogInternal(File log) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				reader.handle(line);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	void updateMatches() {
		displayFooter();
	}

	void updateRounds() {
		DefaultListModel<Round> model = (DefaultListModel<Round>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			//for (Round r : Core.rounds) {
			for (Round r : Core.filtered) {
				if (r.isFallBall() && r.getMe() != null) {
					model.addElement(r);
				}
			}
			//roundsSel.setSelectedIndex(0);
			//roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
			displayStats();
		}
	}

	private void appendToStats(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = statsArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	/*
	private void appendToRoundDetail(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = roundDetailArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}
	*/

	void roundSelected(Round r) {
		if (r == null)
			return;
		refreshRoundDetail(r);
	}

	@Override
	public void showUpdated() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			updateMatches();
		});
	}

	@Override
	public void roundStarted() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			Core.updateStats();
			updateRounds();
		});
	}

	@Override
	public void roundUpdated() {
		/*
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			if (Core.currentRound == getSelectedRound())
				refreshRoundDetail(getSelectedRound());
		});
		*/
	}

	@Override
	public void roundDone() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			Core.updateStats();
			Core.updateAchivements();
			achievementPanel.updateDaily();
			updateRounds();
		});
	}

	Round getSelectedRound() {
		return roundsSel.getSelectedValue();
	}

	private void removePlayerOnCurrentRound() {
		Round r = getSelectedRound();
		Player p = r.getMe();
		r.disableMe = !r.disableMe;
		r.playerCount += r.disableMe ? -1 : 1;
		if (p.isQualified())
			r.qualifiedCount += r.disableMe ? -1 : 1;
		r.playerCountAdd = r.adjustPlayerCount();
		updateRounds();
		Core.updateStats();
		displayStats();
	}

	private void adjustPlayerCountOnCurrentRound() {
		Round r = getSelectedRound();
		r.playerCountAddChanged = !r.playerCountAddChanged;
		if (r.playerCountAdd != 0)
			r.playerCountAdd = 0;
		else
			r.playerCountAdd = -r.playerCount % 2;
		updateRounds();
		Core.updateStats();
		displayStats();
	}

	void refreshRoundDetail(Round r) {
		if (r == null) {
			return;
		}
		/*
		System.out.println(r.topFinish);
		System.out.println(r.myFinish);
		System.out.println(r.end);
		System.out.println("FIXED:" + r.fixed);
		System.out.println("players:" + r.playerCount);
		System.out.println("qualified:" + r.qualifiedCount);
		System.out.println(Arrays.toString(r.teamScore));
		synchronized (Core.listLock) {
			for (Player p : r.byRank()) {
				StringBuilder buf = new StringBuilder();
				buf.append(p.qualified == null ? "　" : p.qualified ? "○" : "✕");
				buf.append(Core.pad(p.ranking)).append(" ");
				buf.append(Core.pad(p.score)).append("pt(").append(p.finalScore < 0 ? "  " : Core.pad(p.finalScore))
						.append(")").append(" ").append(p.partyId != 0 ? Core.pad(p.partyId) + " " : "   ");
				buf.append(r.myPlayerId == p.id ? "★" : "　").append(p);
				System.out.println(new String(buf));
			}
		}
		*/
	}

	void displayStats() {
		statsArea.setText("");

		PlayerStat stat = Core.stat;
		appendToStats(Core.RES.getString("myStatLabel") + stat.winCount + " / " + stat.participationCount + " ("
				+ stat.getRate() + "%)", "bold");
		appendToStats("Total rate: " + stat.totalWinCount + " / " + stat.totalParticipationCount + " ("
				+ Core.calRate(stat.totalWinCount, stat.totalParticipationCount) + "%)", "bold");

		int p = stat.totalPoint();
		appendToStats("Total Points: " + p, "bold");
		/*
		appendToStats("[" + stat.getTitle() + "]", "bold");

		for (int i = 0; i < Core.titledPoints.length; i += 1) {
			if (p < Core.titledPoints[i]) {
				appendToStats("->" + Core.titledPoints[i] + " points", null);
				appendToStats("[" + Core.titles[i + 1] + "]", null);
				break;
			}
		}
		*/

		Match m = Core.currentMatch;
		if (m == null)
			return;
		appendToStats("", null);
		appendToStats("Current Match: ", null);
		appendToStats(m.ip + "(" + m.pingMS + "ms)", null);
		Map<String, String> server = Core.servers.get(m.ip);
		if (server != null)
			appendToStats(server.get("country") + "/" + server.get("regionName"), null);

		statsArea.setCaretPosition(0);

		titlePanel.update();
	}

	static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);

	void displayFooter() {
		String text = "";
		Match m = Core.currentMatch;
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

class AchievementPanel extends JPanel {
	static Font FONT = new Font(FallBallRecord.fontFamily, Font.BOLD, 14);
	Box dailyBox = Box.createVerticalBox();

	AchievementPanel() {
		super(new BorderLayout());
		dailyBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
				BorderFactory.createEmptyBorder(2, 2, 2, 2)));
		add(dailyBox, BorderLayout.NORTH);
		updateDaily();

		Box b = Box.createVerticalBox();
		//b.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
		add(b, BorderLayout.CENTER);
		JLabel l = new JLabel("Achivements", SwingConstants.LEFT);
		l.setFont(FONT);
		b.add(l);
		for (Achievement a : Core.achievements) {
			b.add(a.panel);
		}
	}

	@Override
	public Insets getInsets() {
		return new Insets(4, 4, 4, 4);
	}

	static final Color BACKGROUND = new Color(0xffffcc);
	static final Color BACKGROUND_WEEK = new Color(0xffffee);

	void updateDaily() {
		dailyBox.removeAll();
		int today = Core.toDayKey(new Date());
		int week = Core.toWeekKey(new Date());
		JLabel l = new JLabel("Daily Challenges " + (today / 100 % 100 + 1) + "/" + (today % 100));
		l.setFont(FONT);
		dailyBox.add(l);
		for (Achievement a : Core.getChallenges(today)) {
			dailyBox.add(a.panel);
			a.panel.setBackground(BACKGROUND);
			a.panel.setPreferredSize(new Dimension(80, 40));
		}
		l = new JLabel("Weekly Challenges " + (week / 100) + "/" + (week % 100));
		l.setFont(FONT);
		dailyBox.add(l);
		for (Achievement a : Core.weeklyChallenges) {
			dailyBox.add(a.panel);
			a.panel.setBackground(BACKGROUND_WEEK);
			a.panel.setPreferredSize(new Dimension(80, 40));
		}
		revalidate();
		repaint();
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

class UTF8Control extends Control {
	public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
			throws IllegalAccessException, InstantiationException, IOException {
		// The below is a copy of the default implementation.
		String bundleName = toBundleName(baseName, locale);
		String resourceName = toResourceName(bundleName, "properties");
		ResourceBundle bundle = null;
		InputStream stream = null;
		if (reload) {
			URL url = loader.getResource(resourceName);
			if (url != null) {
				URLConnection connection = url.openConnection();
				if (connection != null) {
					connection.setUseCaches(false);
					stream = connection.getInputStream();
				}
			}
		} else {
			stream = loader.getResourceAsStream(resourceName);
		}
		if (stream != null) {
			try {
				// Only this line is changed to make it to read properties files as UTF-8.
				bundle = new PropertyResourceBundle(new InputStreamReader(stream, "UTF-8"));
			} finally {
				stream.close();
			}
		}
		return bundle;
	}
}
