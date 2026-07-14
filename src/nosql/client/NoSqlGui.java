package nosql.client;

import nosql.common.DbValue;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.Timer;

/**
 * 苹果风极简柔和白色主题 Redis 桌面管理器 (Another Redis Desktop Manager - Elegant Light Edition)
 * 支持全中文界面，具备全局抗锯齿大圆角（16px+）、左侧独立操作区、右侧主展示区自适应主滚动条、顶部功能连接区、大号易读字体与丝滑的操作反馈
 * 支持折线图、磁盘测试仿真窗口和交互控制台通过 JSplitPane 连环上下拖拽、调节高度大小
 * 支持创建/更新键时动态实时刷新 JTree 并自动高亮选中、支持活跃数据键的 TTL 动态生存倒计时计时
 */
public class NoSqlGui {

    private JFrame frame;
    private FlatTextField hostField;
    private FlatTextField portField;
    private FlatButton connectBtn;
    
    // 顶部全局功能按钮
    private FlatButton flushBtn;

    // 左侧 Key 树与快捷操作表单
    private JTree keyTree;
    private DefaultTreeModel treeModel;
    private FlatTextField searchField;
    private FlatButton addKeyBtn;
    private FlatButton refreshBtn;
    private JLabel statusLabel;
    
    // 左侧快捷数据操作框
    private FlatTextField opKeyField;
    private FlatTextField opValField;
    private JComboBox<String> opTypeCombo;
    private FlatButton opWriteBtn;
    private FlatButton opGetBtn;
    private FlatButton opDelBtn;
    private FlatButton opExistsBtn;
    private FlatButton opKeysBtn;
    private FlatButton opTtlBtn;
    private FlatButton msetBtn;

    // 右侧主工作区 (带有主滚动条，排版自适应)
    private JPanel rightMainPanel;
    private CardLayout rightCardLayout;
    private JScrollPane rightScrollPane; // 右侧主展示窗口滚动条
    
    // Dashboard 卡片元素
    private JLabel dbSizeLabel;
    private JLabel memoryLabel;
    private JLabel roleLabel;
    private RoundedPanel roleCard;
    private QpsChartPanel qpsChart;
    private JProgressBar seekProgressBar;
    private JTextArea seekConsole;
    private FlatButton startSeekBtn;

    // Key Editor 卡片元素
    private JLabel selectedKeyTitle;
    private JLabel typeBadge;
    private JLabel ttlStatusLabel;
    private FlatTextField setTtlField;
    private FlatButton saveTtlBtn;
    private FlatButton deleteKeyBtn;
    
    // 具体值编辑器（CardLayout 管理）
    private JPanel valueEditorContainer;
    private CardLayout valueEditorCardLayout;
    
    // String 编辑器
    private JTextArea stringTextArea;
    private FlatButton saveStringBtn;
    
    // List/Set/Hash 共享列表编辑器
    private JTable valueTable;
    private DefaultTableModel valueTableModel;
    private FlatButton addRowBtn;
    private FlatButton removeRowBtn;
    private String currentEditingKey;
    private String currentEditingType;

    // 控制台 Console (作为右侧主展示窗口的一部分)
    private JTextArea consoleOutputArea;
    private FlatTextField consoleInputField;

    private NoSqlSdk sdk;
    private Timer qpsTimer;
    private Timer ttlTimer; // 动态生存时间倒计时定时器
    private int prevOperations = 0;
    private long prevTotalCommands = 0;
    private long prevInfoTime = 0;

    // 全局大号易读字体定义 (上调字号)
    private static final Font FONT_TEXT = new Font("Microsoft YaHei", Font.PLAIN, 15);
    private static final Font FONT_BOLD = new Font("Microsoft YaHei", Font.BOLD, 15);
    private static final Font FONT_HEADER = new Font("Microsoft YaHei", Font.BOLD, 22);
    private static final Font FONT_CODE = new Font("Fira Code", Font.PLAIN, 15);

    // 莫兰迪/马卡龙淡雅配色
    private static final Color COLOR_BG = new Color(241, 245, 249);       // Slate 100 浅灰白底色
    private static final Color COLOR_CARD = new Color(255, 255, 255);     // 纯白圆角面板
    private static final Color COLOR_TEXT = new Color(15, 23, 42);        // Slate 900 优雅深灰色
    private static final Color COLOR_MUTED = new Color(100, 116, 139);    // Slate 500
    private static final Color COLOR_ACCENT = new Color(37, 99, 235);     // 蔚蓝色

    public NoSqlGui() {
        createView();
    }

    private void createView() {
        frame = new JFrame("Another Java-Redis 可视化桌面管理器");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1450, 920);
        frame.setMinimumSize(new Dimension(1100, 760));
        frame.setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new BorderLayout(24, 24));
        rootPanel.setBackground(COLOR_BG);
        rootPanel.setBorder(new EmptyBorder(24, 24, 24, 24));
        frame.setContentPane(rootPanel);

        // ==========================================
        // 1. 顶部功能区 (连接与全局清空，互不影响)
        // ==========================================
        RoundedPanel connectionBar = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        connectionBar.setLayout(new BorderLayout(20, 0));
        connectionBar.setBorder(new EmptyBorder(14, 24, 14, 24));

        JPanel connLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        connLeft.setBackground(COLOR_CARD);
        hostField = new FlatTextField("127.0.0.1", 15);
        portField = new FlatTextField("8080", 6);
        connectBtn = new FlatButton("⚡ 连接服务端");
        connectBtn.setPreferredSize(new Dimension(160, 40));
        connectBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);

        connLeft.add(createLabel("服务IP地址:"));
        connLeft.add(hostField);
        connLeft.add(createLabel("端口:"));
        connLeft.add(portField);
        connLeft.add(connectBtn);
        connectionBar.add(connLeft, BorderLayout.WEST);

        JPanel connRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        connRight.setBackground(COLOR_CARD);
        flushBtn = new FlatButton("🧹 清空所有数据");
        flushBtn.setPreferredSize(new Dimension(160, 40));
        flushBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38));
        flushBtn.setEnabled(false);
        connRight.add(flushBtn);
        connectionBar.add(connRight, BorderLayout.EAST);

        rootPanel.add(connectionBar, BorderLayout.NORTH);

        // ==========================================
        // 2. 左侧边栏 (Sidebar) - 数据操作区 (目录树 + 快捷数据操作表单)
        // ==========================================
        JPanel sidebarPanel = new JPanel(new BorderLayout(0, 18));
        sidebarPanel.setBackground(COLOR_BG);
        sidebarPanel.setPreferredSize(new Dimension(420, 0));

        // 2.1 目录树卡片 (上半部分)
        RoundedPanel treeCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        treeCard.setLayout(new BorderLayout(15, 15));
        treeCard.setBorder(new EmptyBorder(18, 18, 18, 18));

        JPanel searchBar = new JPanel(new BorderLayout(10, 0));
        searchBar.setBackground(COLOR_CARD);
        searchField = new FlatTextField("", 12);
        searchField.setToolTipText("搜索键名 (例: user:*)");

        JPanel treeBtns = new JPanel(new GridLayout(1, 2, 10, 0));
        treeBtns.setBackground(COLOR_CARD);
        addKeyBtn = new FlatButton("➕ 新建键");
        addKeyBtn.setPreferredSize(new Dimension(98, 38));
        addKeyBtn.setThemeColors(new Color(236, 253, 245), new Color(209, 250, 229), new Color(167, 243, 208), new Color(4, 120, 87));
        addKeyBtn.setEnabled(false);

        refreshBtn = new FlatButton("🔄 刷新");
        refreshBtn.setPreferredSize(new Dimension(85, 38));
        refreshBtn.setThemeColors(new Color(248, 250, 252), new Color(241, 245, 249), new Color(226, 232, 240), COLOR_MUTED);
        refreshBtn.setEnabled(false);
        treeBtns.add(addKeyBtn);
        treeBtns.add(refreshBtn);

        searchBar.add(searchField, BorderLayout.CENTER);
        searchBar.add(treeBtns, BorderLayout.EAST);
        treeCard.add(searchBar, BorderLayout.NORTH);

        // JTree
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Redis 数据库连接");
        treeModel = new DefaultTreeModel(rootNode);
        keyTree = new JTree(treeModel);
        keyTree.setBackground(COLOR_CARD);
        keyTree.setForeground(COLOR_TEXT);
        keyTree.setFont(FONT_CODE);
        keyTree.setRowHeight(32); 
        keyTree.setCellRenderer(new KeyTreeCellRenderer());
        keyTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        JScrollPane treeScrollPane = new JScrollPane(keyTree);
        treeScrollPane.setBorder(new RoundedBorder(12, new Color(226, 232, 240))); 
        treeScrollPane.getViewport().setBackground(COLOR_CARD);
        treeCard.add(treeScrollPane, BorderLayout.CENTER);

        statusLabel = new JLabel("运行状态: 未连接");
        statusLabel.setForeground(COLOR_MUTED);
        statusLabel.setFont(FONT_TEXT); 
        treeCard.add(statusLabel, BorderLayout.SOUTH);
        
        sidebarPanel.add(treeCard, BorderLayout.CENTER);

        // 2.2 快捷数据操作表单卡片 (下半部分)
        RoundedPanel quickOpCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        quickOpCard.setLayout(new GridBagLayout());
        quickOpCard.setBorder(new EmptyBorder(20, 20, 20, 20));
        quickOpCard.setPreferredSize(new Dimension(0, 380));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 0, 8, 0);
        gbc.gridx = 0;

        JLabel opTitle = new JLabel("⚡ 快捷数据写/删操作");
        opTitle.setForeground(COLOR_TEXT);
        opTitle.setFont(FONT_BOLD);
        gbc.gridy = 0;
        quickOpCard.add(opTitle, gbc);

        // 键名输入
        JPanel opKeyRow = new JPanel(new BorderLayout(12, 0));
        opKeyRow.setBackground(COLOR_CARD);
        opKeyField = new FlatTextField("", 12);
        opKeyRow.add(createLabel("键名:"), BorderLayout.WEST);
        opKeyRow.add(opKeyField, BorderLayout.CENTER);
        gbc.gridy = 1;
        quickOpCard.add(opKeyRow, gbc);

        // 键值输入
        JPanel opValRow = new JPanel(new BorderLayout(12, 0));
        opValRow.setBackground(COLOR_CARD);
        opValField = new FlatTextField("", 12);
        opValRow.add(createLabel("键值:"), BorderLayout.WEST);
        opValRow.add(opValField, BorderLayout.CENTER);
        gbc.gridy = 2;
        quickOpCard.add(opValRow, gbc);

        // 类型选择
        JPanel opTypeRow = new JPanel(new BorderLayout(10, 0));
        opTypeRow.setBackground(COLOR_CARD);
        opTypeCombo = new JComboBox<>(new String[]{"STRING", "LIST (RPUSH)", "SET (SADD)", "HASH (f:v)"});
        opTypeCombo.setFont(FONT_TEXT);
        opTypeCombo.setBackground(Color.WHITE);
        opTypeRow.add(createLabel("类型:"), BorderLayout.WEST);
        opTypeRow.add(opTypeCombo, BorderLayout.CENTER);
        gbc.gridy = 3;
        quickOpCard.add(opTypeRow, gbc);

        // 操作按钮组合（2行3列：写入/读取/删除 + 检查/列出/生命周期）
        JPanel opBtnRow = new JPanel(new GridLayout(2, 3, 10, 8));
        opBtnRow.setBackground(COLOR_CARD);

        opWriteBtn = new FlatButton("写入 SET");
        opWriteBtn.setThemeColors(new Color(236, 253, 245), new Color(209, 250, 229), new Color(167, 243, 208), new Color(4, 120, 87));
        opWriteBtn.setEnabled(false);

        opGetBtn = new FlatButton("读取 GET");
        opGetBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
        opGetBtn.setEnabled(false);

        opDelBtn = new FlatButton("删除 DEL");
        opDelBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38));
        opDelBtn.setEnabled(false);

        opExistsBtn = new FlatButton("检查 EXISTS");
        opExistsBtn.setThemeColors(new Color(248, 250, 252), new Color(241, 245, 249), new Color(226, 232, 240), COLOR_MUTED);
        opExistsBtn.setEnabled(false);

        opKeysBtn = new FlatButton("列出 KEYS");
        opKeysBtn.setThemeColors(new Color(248, 250, 252), new Color(241, 245, 249), new Color(226, 232, 240), COLOR_MUTED);
        opKeysBtn.setEnabled(false);

        opTtlBtn = new FlatButton("生命 TTL");
        opTtlBtn.setThemeColors(new Color(254, 243, 199), new Color(253, 230, 138), new Color(252, 211, 77), new Color(180, 83, 9));
        opTtlBtn.setEnabled(false);

        opBtnRow.add(opWriteBtn);
        opBtnRow.add(opGetBtn);
        opBtnRow.add(opDelBtn);
        opBtnRow.add(opExistsBtn);
        opBtnRow.add(opKeysBtn);
        opBtnRow.add(opTtlBtn);
        
        gbc.gridy = 4;
        gbc.insets = new Insets(12, 0, 4, 0);
        quickOpCard.add(opBtnRow, gbc);

        // MSET 批量写入按钮行
        JPanel msetRow = new JPanel(new BorderLayout());
        msetRow.setBackground(COLOR_CARD);
        msetBtn = new FlatButton("📦 MSET 批量写入");
        msetBtn.setThemeColors(new Color(236, 253, 245), new Color(209, 250, 229), new Color(167, 243, 208), new Color(4, 120, 87));
        msetBtn.setEnabled(false);
        msetRow.add(msetBtn, BorderLayout.CENTER);
        gbc.gridy = 5;
        gbc.insets = new Insets(4, 0, 0, 0);
        quickOpCard.add(msetRow, gbc);

        sidebarPanel.add(quickOpCard, BorderLayout.SOUTH);

        // ==========================================
        // 3. 右侧主展示窗口 (CardLayout 展示所有数据区)
        // ==========================================
        rightCardLayout = new CardLayout();
        rightMainPanel = new JPanel(rightCardLayout);
        rightMainPanel.setBackground(COLOR_BG);

        // ==========================================
        // 3.1 Card A: Server Dashboard 看板 (含可调节大小的测试仿真窗口与终端)
        // ==========================================
        JPanel dashboardPanel = new JPanel(new BorderLayout(0, 18));
        dashboardPanel.setBackground(COLOR_BG);

        // A1. 顶层三个指标小卡片 (固定在 Dashboard 北部)
        JPanel statsPanel = new JPanel(new GridLayout(1, 3, 20, 0));
        statsPanel.setBackground(COLOR_BG);
        statsPanel.setPreferredSize(new Dimension(0, 130));

        RoundedPanel dbSizeCard = createStatCard(" 键总数 (Keys Count)", dbSizeLabel = new JLabel("0", SwingConstants.CENTER), new Color(239, 246, 255), new Color(191, 219, 254)); 
        RoundedPanel memoryCard = createStatCard(" 内存缓存占用 (Cache)", memoryLabel = new JLabel("0.0 KB", SwingConstants.CENTER), new Color(245, 243, 255), new Color(221, 214, 254)); 
        roleCard = createStatCard(" 集群角色 (Cluster Role)", roleLabel = new JLabel("STANDALONE", SwingConstants.CENTER), new Color(241, 245, 249), new Color(226, 232, 240)); 
        
        statsPanel.add(dbSizeCard);
        statsPanel.add(memoryCard);
        statsPanel.add(roleCard);
        dashboardPanel.add(statsPanel, BorderLayout.NORTH);

        // A2. 中部拖拽分割区 (通过连环 JSplitPane，使折线图、测试仿真、终端均可调节大小)
        // QPS 折线图卡片
        RoundedPanel chartCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        chartCard.setLayout(new BorderLayout());
        chartCard.setBorder(new EmptyBorder(18, 18, 18, 18));
        chartCard.setMinimumSize(new Dimension(0, 130)); 
        chartCard.setPreferredSize(new Dimension(0, 240));
        
        JLabel chartTitle = new JLabel("实时数据库操作吞吐率 (QPS)");
        chartTitle.setForeground(COLOR_TEXT);
        chartTitle.setFont(FONT_BOLD);
        chartCard.add(chartTitle, BorderLayout.NORTH);

        qpsChart = new QpsChartPanel();
        chartCard.add(qpsChart, BorderLayout.CENTER);

        // O(1) 磁盘 Seek 仿真测试卡片
        RoundedPanel seekCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        seekCard.setLayout(new BorderLayout(15, 12));
        seekCard.setBorder(new EmptyBorder(18, 18, 18, 18));
        seekCard.setMinimumSize(new Dimension(0, 130)); 
        seekCard.setPreferredSize(new Dimension(0, 220)); 

        JLabel seekTitle = new JLabel("单机冷热数据 O(1) 磁盘 Seek 测试仿真");
        seekTitle.setForeground(COLOR_TEXT);
        seekTitle.setFont(FONT_BOLD);
        seekCard.add(seekTitle, BorderLayout.NORTH);

        seekConsole = new JTextArea();
        seekConsole.setBackground(new Color(248, 250, 252));
        seekConsole.setForeground(COLOR_TEXT);
        seekConsole.setFont(FONT_CODE);
        seekConsole.setEditable(false);
        seekConsole.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
        JScrollPane seekScroll = new JScrollPane(seekConsole);
        seekScroll.setBorder(new RoundedBorder(12, new Color(226, 232, 240))); 
        seekCard.add(seekScroll, BorderLayout.CENTER);

        JPanel seekCtrl = new JPanel(new BorderLayout(15, 0));
        seekCtrl.setBackground(COLOR_CARD);
        seekProgressBar = new JProgressBar(0, 100);
        seekProgressBar.setStringPainted(true);
        seekProgressBar.setFont(FONT_BOLD);
        seekProgressBar.setBackground(COLOR_BG);
        seekProgressBar.setForeground(COLOR_ACCENT);
        
        startSeekBtn = new FlatButton("🚀 开启磁盘 O(1) Seek 仿真");
        startSeekBtn.setPreferredSize(new Dimension(250, 40));
        startSeekBtn.setThemeColors(new Color(254, 243, 199), new Color(253, 230, 138), new Color(252, 211, 77), new Color(180, 83, 9));
        startSeekBtn.setEnabled(false);
        
        seekCtrl.add(seekProgressBar, BorderLayout.CENTER);
        seekCtrl.add(startSeekBtn, BorderLayout.EAST);
        seekCard.add(seekCtrl, BorderLayout.SOUTH);

        // 底部控制终端 Console 卡片
        RoundedPanel consoleCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        consoleCard.setLayout(new BorderLayout(5, 5));
        consoleCard.setBorder(new EmptyBorder(12, 18, 12, 18));
        consoleCard.setMinimumSize(new Dimension(0, 110)); 
        consoleCard.setPreferredSize(new Dimension(0, 200));

        JLabel consoleTitle = new JLabel("📟 交互式 Redis 命令行终端");
        consoleTitle.setForeground(COLOR_ACCENT);
        consoleTitle.setFont(FONT_BOLD);
        consoleCard.add(consoleTitle, BorderLayout.NORTH);

        consoleOutputArea = new JTextArea();
        consoleOutputArea.setBackground(new Color(248, 250, 252));
        consoleOutputArea.setForeground(new Color(30, 41, 59));
        consoleOutputArea.setFont(FONT_CODE);
        consoleOutputArea.setEditable(false);
        consoleOutputArea.setText("Another Redis 交互式终端。连接服务端后，可输入原生 Redis 指令。\n\n");
        consoleOutputArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
        JScrollPane consoleScroll = new JScrollPane(consoleOutputArea);
        consoleScroll.setBorder(new RoundedBorder(12, new Color(226, 232, 240))); 
        consoleCard.add(consoleScroll, BorderLayout.CENTER);

        consoleInputField = new FlatTextField("", 25);
        consoleInputField.setBackground(COLOR_CARD);
        consoleInputField.setToolTipText("输入命令，敲击回车执行");
        consoleInputField.setEnabled(false);
        consoleCard.add(consoleInputField, BorderLayout.SOUTH);

        // JSplitPane 垂直拖拽配置
        JSplitPane bottomSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, seekCard, consoleCard);
        bottomSplit.setBackground(COLOR_BG);
        bottomSplit.setOpaque(true);
        bottomSplit.setDividerSize(8);
        bottomSplit.setDividerLocation(230);
        bottomSplit.setContinuousLayout(true);
        bottomSplit.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane topSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartCard, bottomSplit);
        topSplit.setBackground(COLOR_BG);
        topSplit.setOpaque(true);
        topSplit.setDividerSize(8);
        topSplit.setDividerLocation(250);
        topSplit.setContinuousLayout(true);
        topSplit.setBorder(BorderFactory.createEmptyBorder());

        dashboardPanel.add(topSplit, BorderLayout.CENTER);
        rightMainPanel.add(dashboardPanel, "DASHBOARD");

        // ==========================================
        // 3.2 Card B: Key Editor 详情编辑器
        // ==========================================
        JPanel editorPanel = new JPanel();
        editorPanel.setBackground(COLOR_BG);
        editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));

        RoundedPanel editorHeaderCard = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        editorHeaderCard.setLayout(new BorderLayout(24, 0));
        editorHeaderCard.setBorder(new EmptyBorder(20, 24, 20, 24));
        editorHeaderCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        editorHeaderCard.setPreferredSize(new Dimension(0, 90));

        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        headerLeft.setBackground(COLOR_CARD);
        selectedKeyTitle = new JLabel("请选择一个键名");
        selectedKeyTitle.setForeground(COLOR_TEXT);
        selectedKeyTitle.setFont(FONT_HEADER);
        
        typeBadge = new JLabel("STRING");
        typeBadge.setOpaque(true);
        typeBadge.setBackground(new Color(37, 99, 235, 15));
        typeBadge.setForeground(COLOR_ACCENT);
        typeBadge.setFont(new Font("Fira Code", Font.BOLD, 13)); 
        typeBadge.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_ACCENT, 1),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));
        headerLeft.add(selectedKeyTitle);
        headerLeft.add(typeBadge);
        editorHeaderCard.add(headerLeft, BorderLayout.WEST);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        headerRight.setBackground(COLOR_CARD);
        ttlStatusLabel = new JLabel("生命周期: 永久 (Persistent)");
        ttlStatusLabel.setForeground(COLOR_MUTED);
        ttlStatusLabel.setFont(FONT_BOLD); 
        
        setTtlField = new FlatTextField("", 6);
        setTtlField.setToolTipText("秒数");
        saveTtlBtn = new FlatButton("设置 TTL");
        saveTtlBtn.setThemeColors(new Color(254, 243, 199), new Color(253, 230, 138), new Color(252, 211, 77), new Color(180, 83, 9));
        saveTtlBtn.setPreferredSize(new Dimension(95, 38));

        deleteKeyBtn = new FlatButton("🗑️ 删除键");
        deleteKeyBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38));
        deleteKeyBtn.setPreferredSize(new Dimension(110, 38));

        headerRight.add(ttlStatusLabel);
        headerRight.add(setTtlField);
        headerRight.add(saveTtlBtn);
        headerRight.add(deleteKeyBtn);
        editorHeaderCard.add(headerRight, BorderLayout.EAST);
        editorPanel.add(editorHeaderCard);
        editorPanel.add(Box.createVerticalStrut(18));

        // 值内容编辑器
        valueEditorCardLayout = new CardLayout();
        valueEditorContainer = new JPanel(valueEditorCardLayout);
        valueEditorContainer.setBackground(COLOR_BG);

        // 3.2.1 STRING 编辑器面板
        RoundedPanel stringPanel = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        stringPanel.setLayout(new BorderLayout(0, 12));
        stringPanel.setBorder(new EmptyBorder(18, 18, 18, 18));
        stringPanel.setPreferredSize(new Dimension(0, 500));

        stringTextArea = new JTextArea();
        stringTextArea.setBackground(new Color(248, 250, 252));
        stringTextArea.setForeground(COLOR_TEXT);
        stringTextArea.setCaretColor(COLOR_TEXT);
        stringTextArea.setFont(FONT_CODE);
        stringTextArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
        JScrollPane stringScroll = new JScrollPane(stringTextArea);
        stringScroll.setBorder(new RoundedBorder(12, new Color(226, 232, 240))); 
        stringPanel.add(stringScroll, BorderLayout.CENTER);

        JPanel stringCtrl = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stringCtrl.setBackground(COLOR_CARD);
        saveStringBtn = new FlatButton("💾 保存修改");
        saveStringBtn.setPreferredSize(new Dimension(170, 42));
        saveStringBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
        stringCtrl.add(saveStringBtn);
        stringPanel.add(stringCtrl, BorderLayout.SOUTH);
        
        valueEditorContainer.add(stringPanel, "STRING_EDITOR");

        // 3.2.2 LIST/SET/HASH 数据表格编辑器面板
        RoundedPanel tableEditorPanel = new RoundedPanel(16, COLOR_CARD, new Color(226, 232, 240));
        tableEditorPanel.setLayout(new BorderLayout(0, 12));
        tableEditorPanel.setBorder(new EmptyBorder(18, 18, 18, 18));
        tableEditorPanel.setPreferredSize(new Dimension(0, 480));

        valueTableModel = new DefaultTableModel();
        valueTable = new JTable(valueTableModel);
        styleTable(valueTable);
        
        JScrollPane valTableScroll = new JScrollPane(valueTable);
        valTableScroll.setBorder(new RoundedBorder(12, new Color(226, 232, 240))); 
        valTableScroll.getViewport().setBackground(COLOR_CARD);
        tableEditorPanel.add(valTableScroll, BorderLayout.CENTER);

        JPanel tableCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        tableCtrl.setBackground(COLOR_CARD);
        addRowBtn = new FlatButton("➕ 增加行");
        addRowBtn.setPreferredSize(new Dimension(120, 40));
        addRowBtn.setThemeColors(new Color(236, 253, 245), new Color(209, 250, 229), new Color(167, 243, 208), new Color(4, 120, 87));

        removeRowBtn = new FlatButton("➖ 删除选中行");
        removeRowBtn.setPreferredSize(new Dimension(150, 40));
        removeRowBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38));
        
        tableCtrl.add(addRowBtn);
        tableCtrl.add(removeRowBtn);
        tableEditorPanel.add(tableCtrl, BorderLayout.SOUTH);

        valueEditorContainer.add(tableEditorPanel, "TABLE_EDITOR");
        editorPanel.add(valueEditorContainer);

        rightMainPanel.add(editorPanel, "EDITOR");

        // ==========================================
        // 4. 追加主滚动条 JScrollPane
        // ==========================================
        rightScrollPane = new JScrollPane(rightMainPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rightScrollPane.setBorder(BorderFactory.createEmptyBorder());
        rightScrollPane.getVerticalScrollBar().setUnitIncrement(16); 
        rightScrollPane.getViewport().setBackground(COLOR_BG);

        // ==========================================
        // 5. 自由调整大小之 JSplitPane (横向分割)
        // ==========================================
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, rightScrollPane);
        mainSplitPane.setBackground(COLOR_BG);
        mainSplitPane.setOpaque(true);
        mainSplitPane.setDividerSize(10);
        mainSplitPane.setDividerLocation(420);
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setBorder(BorderFactory.createEmptyBorder());

        rootPanel.add(mainSplitPane, BorderLayout.CENTER);

        // ==========================================
        // 6. 事件处理器绑定
        // ==========================================
        connectBtn.addActionListener(e -> handleConnect());
        flushBtn.addActionListener(e -> handleFlushAll());
        refreshBtn.addActionListener(e -> handleRefreshKeys());
        addKeyBtn.addActionListener(e -> handleCreateKeyDialog());
        deleteKeyBtn.addActionListener(e -> handleDeleteKey());
        saveTtlBtn.addActionListener(e -> handleSaveTtl());
        saveStringBtn.addActionListener(e -> handleSaveString());
        addRowBtn.addActionListener(e -> handleAddTableRow());
        removeRowBtn.addActionListener(e -> handleRemoveTableRow());
        startSeekBtn.addActionListener(e -> handleSeekSimulation());
        
        opWriteBtn.addActionListener(e -> handleOpWrite());
        opGetBtn.addActionListener(e -> handleOpGet());
        opDelBtn.addActionListener(e -> handleOpDelete());
        opExistsBtn.addActionListener(e -> handleOpExists());
        opKeysBtn.addActionListener(e -> handleOpKeys());
        opTtlBtn.addActionListener(e -> handleOpTtl());
        msetBtn.addActionListener(e -> handleMsetDialog());

        searchField.addActionListener(e -> handleRefreshKeys());
        consoleInputField.addActionListener(e -> handleConsoleCommand());
        keyTree.addTreeSelectionListener(e -> handleTreeSelection());
        
        qpsTimer = new Timer(1000, ev -> updateDashboardQps());
        
        // 🚨 开启 TTL 动态生存倒计时定时器 (每 1 秒更新一次)
        ttlTimer = new Timer(1000, ev -> updateActiveKeyTtl());
        ttlTimer.start();
    }

    // ==========================================
    // 7. 高颜值统一圆角对话窗辅助函数
    // ==========================================
    private void showMsg(String title, String msg, boolean isError) {
        JDialog dialog = new JDialog(frame, title, true);
        dialog.setSize(400, 210);
        dialog.setLocationRelativeTo(frame);
        
        JPanel p = new JPanel(new BorderLayout(15, 15));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(22, 22, 22, 22));
        
        JLabel iconLabel = new JLabel(isError ? "❌" : "✅", SwingConstants.CENTER);
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
        p.add(iconLabel, BorderLayout.WEST);
        
        JTextArea text = new JTextArea(msg);
        text.setFont(FONT_TEXT);
        text.setForeground(COLOR_TEXT);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(Color.WHITE);
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        
        FlatButton okBtn = new FlatButton("好的");
        okBtn.setPreferredSize(new Dimension(85, 36));
        okBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
        okBtn.addActionListener(e -> dialog.dispose());
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.setBackground(Color.WHITE);
        btnPanel.add(okBtn);
        p.add(btnPanel, BorderLayout.SOUTH);
        
        dialog.setContentPane(p);
        dialog.setVisible(true);
    }

    private boolean showConfirm(String title, String msg) {
        final boolean[] result = {false};
        JDialog dialog = new JDialog(frame, title, true);
        dialog.setSize(400, 210);
        dialog.setLocationRelativeTo(frame);
        
        JPanel p = new JPanel(new BorderLayout(15, 15));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(22, 22, 22, 22));
        
        JLabel iconLabel = new JLabel("❓", SwingConstants.CENTER);
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
        p.add(iconLabel, BorderLayout.WEST);
        
        JTextArea text = new JTextArea(msg);
        text.setFont(FONT_TEXT);
        text.setForeground(COLOR_TEXT);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(Color.WHITE);
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        
        FlatButton cancelBtn = new FlatButton("取消");
        cancelBtn.setPreferredSize(new Dimension(85, 36));
        cancelBtn.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240), new Color(203, 213, 225), COLOR_TEXT);
        cancelBtn.addActionListener(e -> dialog.dispose());
        
        FlatButton okBtn = new FlatButton("确认");
        okBtn.setPreferredSize(new Dimension(85, 36));
        okBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38)); 
        okBtn.addActionListener(e -> {
            result[0] = true;
            dialog.dispose();
        });
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        btnPanel.setBackground(Color.WHITE);
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);
        p.add(btnPanel, BorderLayout.SOUTH);
        
        dialog.setContentPane(p);
        dialog.setVisible(true);
        return result[0];
    }

    private String showInput(String title, String msg) {
        final String[] result = {null};
        JDialog dialog = new JDialog(frame, title, true);
        dialog.setSize(420, 220);
        dialog.setLocationRelativeTo(frame);
        
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(20, 22, 20, 22));
        
        JLabel msgLabel = createLabel(msg);
        p.add(msgLabel, BorderLayout.NORTH);
        
        FlatTextField inputField = new FlatTextField("", 20);
        p.add(inputField, BorderLayout.CENTER);
        
        FlatButton cancelBtn = new FlatButton("取消");
        cancelBtn.setPreferredSize(new Dimension(85, 36));
        cancelBtn.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240), new Color(203, 213, 225), COLOR_TEXT);
        cancelBtn.addActionListener(e -> dialog.dispose());
        
        FlatButton okBtn = new FlatButton("确定");
        okBtn.setPreferredSize(new Dimension(85, 36));
        okBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
        okBtn.addActionListener(e -> {
            result[0] = inputField.getText().trim();
            dialog.dispose();
        });
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        btnPanel.setBackground(Color.WHITE);
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);
        p.add(btnPanel, BorderLayout.SOUTH);
        
        dialog.setContentPane(p);
        dialog.setVisible(true);
        return result[0];
    }

    private String[] showHashAddDialog() {
        final String[] result = {null, null};
        JDialog dialog = new JDialog(frame, "➕ 添加 Hash 属性对", true);
        dialog.setSize(420, 260);
        dialog.setLocationRelativeTo(frame);
        
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(20, 22, 20, 22));
        
        JPanel form = new JPanel(new GridLayout(4, 1, 5, 5));
        form.setBackground(Color.WHITE);
        
        FlatTextField fField = new FlatTextField("", 15);
        FlatTextField vField = new FlatTextField("", 15);
        
        form.add(createLabel("属性名 (Field):"));
        form.add(fField);
        form.add(createLabel("属性值 (Value):"));
        form.add(vField);
        p.add(form, BorderLayout.CENTER);
        
        FlatButton cancelBtn = new FlatButton("取消");
        cancelBtn.setPreferredSize(new Dimension(85, 36));
        cancelBtn.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240), new Color(203, 213, 225), COLOR_TEXT);
        cancelBtn.addActionListener(e -> dialog.dispose());
        
        FlatButton okBtn = new FlatButton("确定");
        okBtn.setPreferredSize(new Dimension(85, 36));
        okBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
        okBtn.addActionListener(e -> {
            result[0] = fField.getText().trim();
            result[1] = vField.getText().trim();
            dialog.dispose();
        });
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        btnPanel.setBackground(Color.WHITE);
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);
        p.add(btnPanel, BorderLayout.SOUTH);
        
        dialog.setContentPane(p);
        dialog.setVisible(true);
        
        if (result[0] == null || result[0].isEmpty()) return null;
        return result;
    }

    // ==========================================
    // 8. 控制连接与全局清空逻辑
    // ==========================================
    private void handleConnect() {
        if (sdk != null) {
            try {
                sdk.close();
            } catch (IOException ignored) {}
            sdk = null;
            connectBtn.setText("⚡ 连接服务端");
            connectBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);
            
            refreshBtn.setEnabled(false);
            addKeyBtn.setEnabled(false);
            flushBtn.setEnabled(false);
            startSeekBtn.setEnabled(false);
            consoleInputField.setEnabled(false);
            
            opWriteBtn.setEnabled(false);
            opGetBtn.setEnabled(false);
            opDelBtn.setEnabled(false);
            opExistsBtn.setEnabled(false);
            opKeysBtn.setEnabled(false);
            opTtlBtn.setEnabled(false);
            msetBtn.setEnabled(false);

            statusLabel.setText("运行状态: 未连接");
            qpsTimer.stop();
            prevOperations = 0;
            prevTotalCommands = 0;
            prevInfoTime = 0;
            
            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("未连接");
            treeModel.setRoot(rootNode);
            
            rightCardLayout.show(rightMainPanel, "DASHBOARD");
            return;
        }

        String host = hostField.getText().trim();
        int port = Integer.parseInt(portField.getText().trim());

        try {
            sdk = new NoSqlSdk(host, port);
            connectBtn.setText("❌ 断开连接");
            connectBtn.setThemeColors(new Color(254, 226, 226), new Color(254, 202, 202), new Color(252, 165, 165), new Color(220, 38, 38));
            
            refreshBtn.setEnabled(true);
            addKeyBtn.setEnabled(true);
            flushBtn.setEnabled(true);
            startSeekBtn.setEnabled(true);
            consoleInputField.setEnabled(true);
            
            opWriteBtn.setEnabled(true);
            opGetBtn.setEnabled(true);
            opDelBtn.setEnabled(true);
            opExistsBtn.setEnabled(true);
            opKeysBtn.setEnabled(true);
            opTtlBtn.setEnabled(true);
            msetBtn.setEnabled(true);

            statusLabel.setText("已成功连接至 " + host + ":" + port);
            consoleOutputArea.append("已成功连接至服务端: " + host + ":" + port + "\n");
            
            qpsTimer.start();
            handleRefreshKeys();
            
        } catch (Exception ex) {
            showMsg("连接失败", "无法连接至服务端: " + ex.getMessage(), true);
        }
    }

    private void handleFlushAll() {
        boolean confirmed = showConfirm("FLUSHALL 警告", "警告: 这将删除当前数据库中的所有 Key，且不可逆！\n您确定要清空数据库吗？");
        if (confirmed) {
            try {
                sdk.sendCommand(List.of("FLUSHALL"));
                opKeyField.setText("");
                opValField.setText("");
                handleRefreshKeys();
                rightCardLayout.show(rightMainPanel, "DASHBOARD");
                showMsg("操作成功", "数据库所有数据已成功清空！", false);
            } catch (Exception ex) {
                showMsg("操作失败", "清空失败: " + ex.getMessage(), true);
            }
        }
    }

    // ==========================================
    // 9. 左侧独立数据写/删快捷操作逻辑 (动态刷新联动)
    // ==========================================
    private void handleOpWrite() {
        if (sdk == null) return;
        String key = opKeyField.getText().trim();
        String val = opValField.getText().trim();
        String type = opTypeCombo.getSelectedItem().toString();

        if (key.isEmpty()) {
            showMsg("写入警告", "数据键名不能为空。", true);
            return;
        }

        try {
            if (type.startsWith("STRING")) {
                sdk.sendCommand(List.of("SET", key, val));
            } else if (type.startsWith("LIST")) {
                sdk.sendCommand(List.of("RPUSH", key, val));
            } else if (type.startsWith("SET")) {
                sdk.sendCommand(List.of("SADD", key, val));
            } else if (type.startsWith("HASH")) {
                String[] parts = val.split(":");
                if (parts.length == 2) {
                    sdk.sendCommand(List.of("HSET", key, parts[0].trim(), parts[1].trim()));
                } else {
                    sdk.sendCommand(List.of("HSET", key, "field1", val));
                }
            }
            
            opKeyField.setText("");
            opValField.setText("");
            
            // 🚨 1. 动态刷新左侧的 JTree
            handleRefreshKeys();
            
            // 🚨 2. 自动在左侧树高亮选中新写入的 Key，实现无感动态切换
            selectKeyInTree(key);
            
            consoleOutputArea.append("快捷写入成功: " + key + "\n");
        } catch (Exception ex) {
            showMsg("写入失败", "操作异常: " + ex.getMessage(), true);
        }
    }

    private void handleOpGet() {
        if (sdk == null) return;
        String key = opKeyField.getText().trim();
        if (key.isEmpty()) {
            showMsg("读取警告", "请输入要读取的键名。", true);
            return;
        }
        try {
            String val = (String) sdk.sendCommand(List.of("GET", key));
            if (val != null) {
                opValField.setText(val);
                consoleOutputArea.append("读取键 " + key + ": " + val + "\n");
            } else {
                opValField.setText("");
                showMsg("读取结果", "键 " + key + " 不存在 (nil)。", false);
            }
        } catch (Exception ex) {
            showMsg("读取失败", "操作异常: " + ex.getMessage(), true);
        }
    }

    private void handleOpExists() {
        if (sdk == null) return;
        String key = opKeyField.getText().trim();
        if (key.isEmpty()) {
            showMsg("检查警告", "请输入要检查的键名。", true);
            return;
        }
        try {
            Long count = (Long) sdk.sendCommand(List.of("EXISTS", key));
            boolean exists = count != null && count > 0;
            if (exists) {
                showMsg("键存在", "键 " + key + " 存在于数据库中。", false);
                consoleOutputArea.append("EXISTS " + key + " → true\n");
            } else {
                showMsg("键不存在", "键 " + key + " 不存在。", false);
                consoleOutputArea.append("EXISTS " + key + " → false\n");
            }
        } catch (Exception ex) {
            showMsg("检查失败", "操作异常: " + ex.getMessage(), true);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleOpKeys() {
        if (sdk == null) return;
        String pattern = opKeyField.getText().trim();
        if (pattern.isEmpty()) pattern = "*";
        try {
            List<Object> keys = (List<Object>) sdk.sendCommand(List.of("KEYS", pattern));
            if (keys != null && !keys.isEmpty()) {
                StringBuilder sb = new StringBuilder("匹配 " + pattern + " 的键 (共 " + keys.size() + " 个):\n");
                int i = 1;
                for (Object k : keys) {
                    sb.append("  ").append(i++).append(") ").append(k).append("\n");
                }
                showMsg("KEYS 查询结果", sb.toString(), false);
                consoleOutputArea.append("KEYS " + pattern + " → " + keys.size() + " 个键\n");
            } else {
                showMsg("KEYS 查询结果", "没有键匹配模式: " + pattern, false);
                consoleOutputArea.append("KEYS " + pattern + " → 0 个键\n");
            }
            // 同步刷新树
            handleRefreshKeys();
        } catch (Exception ex) {
            showMsg("KEYS 失败", "操作异常: " + ex.getMessage(), true);
        }
    }

    private void handleOpDelete() {
        if (sdk == null) return;
        String key = opKeyField.getText().trim();
        if (key.isEmpty()) {
            showMsg("删除警告", "请输入要删除的键名。", true);
            return;
        }

        try {
            Long success = (Long) sdk.sendCommand(List.of("DEL", key));
            if (success > 0) {
                opKeyField.setText("");
                opValField.setText("");

                // 🚨 动态刷新并踢回主页
                handleRefreshKeys();
                if (key.equals(currentEditingKey)) {
                    currentEditingKey = null;
                    rightCardLayout.show(rightMainPanel, "DASHBOARD");
                }
                consoleOutputArea.append("成功删除键: " + key + "\n");
            } else {
                showMsg("删除失败", "该键在数据库中不存在。", true);
            }
        } catch (Exception ex) {
            showMsg("操作异常", "删除失败: " + ex.getMessage(), true);
        }
    }

    private void handleOpTtl() {
        if (sdk == null) return;
        String key = opKeyField.getText().trim();
        if (key.isEmpty()) {
            showMsg("生命周期设置", "请在输入框内输入要设置生命期的键名。", true);
            return;
        }

        try {
            Long ttlVal = (Long) sdk.sendCommand(List.of("TTL", key));
            String curTtl = ttlVal == -1 ? "永久" : (ttlVal == -2 ? "已过期" : ttlVal + " 秒");
            
            String val = showInput("设置生存周期 TTL", 
                "键名: " + key + " (当前生存周期: " + curTtl + ")\n请输入新的生存时间 (秒):"
            );

            if (val != null && !val.trim().isEmpty()) {
                int seconds = Integer.parseInt(val.trim());
                Long success = (Long) sdk.sendCommand(List.of("EXPIRE", key, String.valueOf(seconds)));
                if (success == 1L) {
                    handleRefreshKeys();
                    if (key.equals(currentEditingKey)) {
                        loadKeyDetailsFromServer(key);
                    }
                    consoleOutputArea.append("成功将键 " + key + " 的生存期设置为 " + seconds + " 秒\n");
                } else {
                    showMsg("设置失败", "数据键设置失败，该键可能不存在。", true);
                }
            }
        } catch (Exception ex) {
            showMsg("操作异常", "生命周期设置失败: " + ex.getMessage(), true);
        }
    }

    // ==========================================
    // 9b. MSET 批量插入对话框
    // ==========================================
    @SuppressWarnings("unchecked")
    private void handleMsetDialog() {
        if (sdk == null) return;

        JDialog dialog = new JDialog(frame, "📦 MSET 批量写入", true);
        dialog.setSize(600, 480);
        dialog.setLocationRelativeTo(frame);

        JPanel p = new JPanel(new BorderLayout(15, 15));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel tip = new JLabel("<html><b>每行一条</b>，格式：<code>key = value</code>，空行和 # 注释行自动跳过</html>");
        tip.setFont(FONT_TEXT);

        JTextArea inputArea = new JTextArea();
        inputArea.setFont(FONT_CODE);
        inputArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setText("# 示例：批量插入用户数据\nuser:1 = 张三\nuser:2 = 李四\nuser:3 = 王五\nscore:math = 95\nscore:eng = 87\n");

        JScrollPane scroll = new JScrollPane(inputArea);
        scroll.setBorder(new RoundedBorder(10, new Color(226, 232, 240)));

        JLabel resultLabel = new JLabel(" ");
        resultLabel.setFont(FONT_TEXT);

        JPanel bottom = new JPanel(new BorderLayout(15, 0));
        bottom.setBackground(Color.WHITE);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        btnPanel.setBackground(Color.WHITE);

        FlatButton cancelBtn = new FlatButton("取消");
        cancelBtn.setPreferredSize(new Dimension(85, 36));
        cancelBtn.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240), new Color(203, 213, 225), COLOR_TEXT);

        FlatButton execBtn = new FlatButton("▶ 执行批量写入");
        execBtn.setPreferredSize(new Dimension(160, 40));
        execBtn.setThemeColors(new Color(236, 253, 245), new Color(209, 250, 229), new Color(167, 243, 208), new Color(4, 120, 87));

        btnPanel.add(cancelBtn);
        btnPanel.add(execBtn);

        bottom.add(resultLabel, BorderLayout.WEST);
        bottom.add(btnPanel, BorderLayout.EAST);

        p.add(tip, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);

        dialog.setContentPane(p);

        execBtn.addActionListener(ev -> {
            String text = inputArea.getText().trim();
            if (text.isEmpty()) {
                resultLabel.setText("⚠️ 请输入要写入的键值对");
                return;
            }

            String[] lines = text.split("\n");
            List<String> msetArgs = new ArrayList<>();
            int totalPairs = 0, skipped = 0;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    if (!line.isEmpty()) skipped++;
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    skipped++;
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.isEmpty()) { skipped++; continue; }
                msetArgs.add(key);
                msetArgs.add(val);
                totalPairs++;
            }

            if (totalPairs == 0) {
                resultLabel.setText("⚠️ 未解析到有效的键值对，请检查格式");
                return;
            }

            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("MSET");
                cmd.addAll(msetArgs);
                long inserted = (Long) sdk.sendCommand(cmd);
                refreshKeysLater();
                resultLabel.setText("✅ 成功写入 " + inserted + " 条" + (skipped > 0 ? "（跳过 " + skipped + " 行无效数据）" : ""));
                consoleOutputArea.append("MSET 批量写入 " + inserted + " 条记录\n");
            } catch (Exception ex) {
                resultLabel.setText("❌ 写入失败: " + ex.getMessage());
            }
        });

        cancelBtn.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
    }

    private void refreshKeysLater() {
        SwingUtilities.invokeLater(this::handleRefreshKeys);
    }

    // ==========================================
    // 10. Key 目录树构建与刷新与自动选中
    // ==========================================
    @SuppressWarnings("unchecked")
    private void handleRefreshKeys() {
        if (sdk == null) return;
        try {
            String filter = searchField.getText().trim();
            List<Object> keys;
            if (filter.isEmpty() || "*".equals(filter)) {
                keys = (List<Object>) sdk.sendCommand(List.of("KEYS"));
            } else {
                keys = (List<Object>) sdk.sendCommand(List.of("KEYS", filter));
            }
            
            buildKeyTree(keys);
            
            if (keys != null) {
                dbSizeLabel.setText(String.valueOf(keys.size()));
            }
            
        } catch (Exception e) {
            consoleOutputArea.append("刷新目录出错: " + e.getMessage() + "\n");
        }
    }

    private void buildKeyTree(List<Object> keys) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("连接 (" + hostField.getText() + ":" + portField.getText() + ")");
        if (keys != null) {
            List<String> sortedKeys = new ArrayList<>();
            for (Object k : keys) sortedKeys.add(k.toString());
            Collections.sort(sortedKeys);

            for (String key : sortedKeys) {
                String[] segments = key.split(":");
                DefaultMutableTreeNode current = root;
                for (int i = 0; i < segments.length; i++) {
                    current = getOrCreateChildNode(current, segments[i], i == segments.length - 1, key);
                }
            }
        }
        treeModel.setRoot(root);
        
        for (int i = 0; i < keyTree.getRowCount(); i++) {
            keyTree.expandRow(i);
        }
    }

    private DefaultMutableTreeNode getOrCreateChildNode(DefaultMutableTreeNode parent, String segment, boolean isLeaf, String fullKey) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof KeyNodeInfo) {
                KeyNodeInfo info = (KeyNodeInfo) child.getUserObject();
                if (info.segment.equals(segment) && !info.isLeaf) {
                    return child;
                }
            }
        }
        
        KeyNodeInfo newInfo = new KeyNodeInfo(segment, isLeaf, fullKey);
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(newInfo);
        parent.add(newNode);
        return newNode;
    }

    // 🚨 辅助高光选中指定 KeyNode 路径方法 (实现无感动态刷新)
    private void selectKeyInTree(String fullKey) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<?> e = root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
            if (node.getUserObject() instanceof KeyNodeInfo) {
                KeyNodeInfo info = (KeyNodeInfo) node.getUserObject();
                if (info.isLeaf && fullKey.equals(info.fullKey)) {
                    TreePath path = new TreePath(node.getPath());
                    keyTree.setSelectionPath(path);
                    keyTree.scrollPathToVisible(path);
                    break;
                }
            }
        }
    }

    // ==========================================
    // 11. 选中树节点后的逻辑
    // ==========================================
    private void handleTreeSelection() {
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) keyTree.getLastSelectedPathComponent();
        if (selectedNode == null) return;
        
        Object userObj = selectedNode.getUserObject();
        if (userObj instanceof KeyNodeInfo) {
            KeyNodeInfo info = (KeyNodeInfo) userObj;
            if (info.isLeaf) {
                opKeyField.setText(info.fullKey);
                loadKeyDetailsFromServer(info.fullKey);
            } else {
                currentEditingKey = null; // 切回看板时关闭生存倒计时
                rightCardLayout.show(rightMainPanel, "DASHBOARD");
            }
        } else {
            currentEditingKey = null;
            rightCardLayout.show(rightMainPanel, "DASHBOARD");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadKeyDetailsFromServer(String key) {
        if (sdk == null) return;
        try {
            currentEditingKey = key;
            selectedKeyTitle.setText(key);
            
            String type = (String) sdk.sendCommand(List.of("TYPE", key));
            currentEditingType = type;
            typeBadge.setText(type.toUpperCase());
            
            // 初始读取生存周期
            Long ttlVal = (Long) sdk.sendCommand(List.of("TTL", key));
            if (ttlVal == -1) {
                ttlStatusLabel.setText("生命周期: 永久 (Persistent)");
            } else if (ttlVal == -2) {
                ttlStatusLabel.setText("生命周期: 已过期 (Expired)");
            } else {
                ttlStatusLabel.setText("剩余生命: " + ttlVal + " 秒");
            }
            
            if ("string".equalsIgnoreCase(type)) {
                String val = (String) sdk.sendCommand(List.of("GET", key));
                stringTextArea.setText(val != null ? val : "");
                valueEditorCardLayout.show(valueEditorContainer, "STRING_EDITOR");
            } else {
                valueTableModel.setRowCount(0);
                valueTableModel.setColumnCount(0);
                
                if ("list".equalsIgnoreCase(type)) {
                    valueTableModel.addColumn("Index (索引)");
                    valueTableModel.addColumn("Value (值)");
                    List<Object> list = (List<Object>) sdk.sendCommand(List.of("LRANGE", key, "0", "-1"));
                    if (list != null) {
                        for (int i = 0; i < list.size(); i++) {
                            valueTableModel.addRow(new Object[]{i, list.get(i)});
                        }
                    }
                } else if ("set".equalsIgnoreCase(type)) {
                    valueTableModel.addColumn("Member (成员值)");
                    List<Object> set = (List<Object>) sdk.sendCommand(List.of("SMEMBERS", key));
                    if (set != null) {
                        for (Object member : set) {
                            valueTableModel.addRow(new Object[]{member});
                        }
                    }
                } else if ("hash".equalsIgnoreCase(type)) {
                    valueTableModel.addColumn("Field (属性名)");
                    valueTableModel.addColumn("Value (属性值)");
                    List<Object> entries = (List<Object>) sdk.sendCommand(List.of("HGETALL", key));
                    if (entries != null) {
                        for (int i = 0; i < entries.size(); i += 2) {
                            valueTableModel.addRow(new Object[]{entries.get(i), entries.get(i + 1)});
                        }
                    }
                }
                valueEditorCardLayout.show(valueEditorContainer, "TABLE_EDITOR");
            }
            
            rightCardLayout.show(rightMainPanel, "EDITOR");
            SwingUtilities.invokeLater(() -> rightScrollPane.getVerticalScrollBar().setValue(0));
            
        } catch (Exception ex) {
            consoleOutputArea.append("读取键 " + key + " 详情失败: " + ex.getMessage() + "\n");
        }
    }

    // 🚨 定时器执行方法：每秒查询一次活跃 Key 的 TTL 生存状态 (倒计时机制)
    private void updateActiveKeyTtl() {
        if (sdk == null || currentEditingKey == null) return;
        try {
            Long ttlVal = (Long) sdk.sendCommand(List.of("TTL", currentEditingKey));
            if (ttlVal == -1) {
                ttlStatusLabel.setText("生命周期: 永久 (Persistent)");
            } else if (ttlVal == -2) {
                // 该 Key 已经在服务端过期，自动从左侧目录移除，并踢出详情页面回到 Dashboard
                String expiredKey = currentEditingKey;
                currentEditingKey = null;
                ttlStatusLabel.setText("生命周期: 已过期 (Expired)");
                handleRefreshKeys(); 
                rightCardLayout.show(rightMainPanel, "DASHBOARD");
                showMsg("生命过期回收通知", "数据键 [" + expiredKey + "] 生存期已结束，已从服务端和目录树中动态回收删除。", false);
            } else {
                ttlStatusLabel.setText("剩余生命: " + ttlVal + " 秒");
            }
        } catch (Exception ex) {
            // 忽略连接波动异常，不弹框打扰用户
        }
    }

    // ==========================================
    // 13. 卡片数据编辑逻辑
    // ==========================================
    private void handleCreateKeyDialog() {
        if (sdk == null) return;

        JDialog dialog = new JDialog(frame, "➕ 新建数据键 (Add New Key)", true);
        dialog.setSize(520, 460);
        dialog.setLocationRelativeTo(frame);

        JPanel p = new JPanel(new BorderLayout(18, 18));
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(24, 28, 24, 28));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(8, 0, 8, 0);
        g.gridx = 0;

        g.gridy = 0;
        form.add(createLabel("键名 (Key):"), g);
        FlatTextField kField = new FlatTextField("", 20);
        kField.setToolTipText("例如 user:1001:info");
        g.gridy = 1;
        form.add(kField, g);

        g.gridy = 2;
        form.add(createLabel("数据类型 (Type):"), g);
        JComboBox<String> tCombo = new JComboBox<>(new String[]{"STRING (字符串)", "LIST (双端列表)", "SET (无序集合)", "HASH (哈希表)"});
        tCombo.setFont(FONT_TEXT);
        tCombo.setBackground(Color.WHITE);
        g.gridy = 3;
        form.add(tCombo, g);

        g.gridy = 4;
        JLabel valLabel = createLabel("初始值: (STRING 文本内容)");
        form.add(valLabel, g);
        FlatTextField vField = new FlatTextField("", 20);
        vField.setToolTipText("请输入初始字符串内容...");
        g.gridy = 5;
        form.add(vField, g);

        tCombo.addActionListener(e -> {
            int idx = tCombo.getSelectedIndex();
            if (idx == 0) {
                valLabel.setText("初始值: (STRING 文本内容)");
                vField.setToolTipText("请输入初始字符串内容...");
            } else if (idx == 1) {
                valLabel.setText("初始值: (多元素用英文逗号 , 分隔批量推入)");
                vField.setToolTipText("例如: value1,value2,value3");
            } else if (idx == 2) {
                valLabel.setText("初始值: (多成员用英文逗号 , 分隔批量写入)");
                vField.setToolTipText("例如: member1,member2,member3");
            } else if (idx == 3) {
                valLabel.setText("初始值: (属性对格式: field:value，多属性用逗号分隔)");
                vField.setToolTipText("例如: age:20,gender:male,name:lzh");
            }
        });

        p.add(form, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        btns.setBackground(Color.WHITE);
        
        FlatButton cancelBtn = new FlatButton("取消");
        cancelBtn.setPreferredSize(new Dimension(85, 36));
        cancelBtn.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240), new Color(203, 213, 225), COLOR_TEXT);
        
        FlatButton okBtn = new FlatButton("确认创建");
        okBtn.setPreferredSize(new Dimension(110, 36));
        okBtn.setThemeColors(new Color(239, 246, 255), new Color(219, 234, 254), new Color(191, 219, 254), COLOR_ACCENT);

        btns.add(cancelBtn);
        btns.add(okBtn);
        p.add(btns, BorderLayout.SOUTH);

        dialog.setContentPane(p);

        cancelBtn.addActionListener(ev -> dialog.dispose());
        okBtn.addActionListener(ev -> {
            String key = kField.getText().trim();
            String val = vField.getText().trim();
            int idx = tCombo.getSelectedIndex();

            if (key.isEmpty()) {
                showMsg("警告", "数据键名 (Key) 不能为空！", true);
                return;
            }

            try {
                if (idx == 0) {
                    sdk.sendCommand(List.of("SET", key, val));
                } else if (idx == 1) {
                    if (val.contains(",")) {
                        String[] items = val.split(",");
                        for (String item : items) {
                            if (!item.trim().isEmpty()) {
                                sdk.sendCommand(List.of("RPUSH", key, item.trim()));
                            }
                        }
                    } else {
                        sdk.sendCommand(List.of("RPUSH", key, val));
                    }
                } else if (idx == 2) {
                    if (val.contains(",")) {
                        String[] items = val.split(",");
                        for (String item : items) {
                            if (!item.trim().isEmpty()) {
                                sdk.sendCommand(List.of("SADD", key, item.trim()));
                            }
                        }
                    } else {
                        sdk.sendCommand(List.of("SADD", key, val));
                    }
                } else if (idx == 3) {
                    if (val.contains(",")) {
                        String[] pairs = val.split(",");
                        for (String pair : pairs) {
                            String[] kv = pair.split(":");
                            if (kv.length == 2) {
                                sdk.sendCommand(List.of("HSET", key, kv[0].trim(), kv[1].trim()));
                            }
                        }
                    } else {
                        String[] parts = val.split(":");
                        if (parts.length == 2) {
                            sdk.sendCommand(List.of("HSET", key, parts[0].trim(), parts[1].trim()));
                        } else {
                            sdk.sendCommand(List.of("HSET", key, "field1", val));
                        }
                    }
                }
                
                // 🚨 1. 动态刷新左侧的 JTree
                handleRefreshKeys();
                
                // 🚨 2. 自动高亮高光选中新创建的 Key，并在右侧直接展示编辑器
                selectKeyInTree(key);
                
                consoleOutputArea.append("成功新建数据键: " + key + "\n");
                dialog.dispose();
            } catch (Exception ex) {
                showMsg("错误", "数据键创建失败: " + ex.getMessage(), true);
            }
        });

        dialog.setVisible(true);
    }

    private void handleDeleteKey() {
        if (currentEditingKey == null || sdk == null) return;
        boolean confirmed = showConfirm("删除确认", "您确定要删除键: " + currentEditingKey + " 吗？");
        if (confirmed) {
            try {
                sdk.sendCommand(List.of("DEL", currentEditingKey));
                String deletedKey = currentEditingKey;
                currentEditingKey = null;
                
                // 🚨 动态刷新并切回 Dashboard 看板
                handleRefreshKeys();
                rightCardLayout.show(rightMainPanel, "DASHBOARD");
                consoleOutputArea.append("成功删除数据键: " + deletedKey + "\n");
            } catch (Exception ex) {
                showMsg("删除失败", "删除键失败: " + ex.getMessage(), true);
            }
        }
    }

    private void handleSaveTtl() {
        if (currentEditingKey == null || sdk == null) return;
        String ttlStr = setTtlField.getText().trim();
        if (ttlStr.isEmpty()) return;
        try {
            int seconds = Integer.parseInt(ttlStr);
            sdk.sendCommand(List.of("EXPIRE", currentEditingKey, String.valueOf(seconds)));
            setTtlField.setText("");
            // 🚨 动态重载详情 (让倒计时立刻生效)
            loadKeyDetailsFromServer(currentEditingKey);
            handleRefreshKeys();
        } catch (Exception ex) {
            showMsg("设置生命周期失败", "生命周期 TTL 格式错误: " + ex.getMessage(), true);
        }
    }

    private void handleSaveString() {
        if (currentEditingKey == null || sdk == null) return;
        try {
            String val = stringTextArea.getText();
            sdk.sendCommand(List.of("SET", currentEditingKey, val));
            showMsg("保存成功", "数据修改并保存成功！", false);
            // 🚨 同时动态更新左侧树以防 key 过期状态有变
            handleRefreshKeys();
        } catch (Exception ex) {
            showMsg("保存失败", "写入异常: " + ex.getMessage(), true);
        }
    }

    private void handleAddTableRow() {
        if (currentEditingKey == null || sdk == null) return;
        
        if ("list".equalsIgnoreCase(currentEditingType)) {
            String val = showInput("推入 List 元素", "请输入要推入双端列表的值:");
            if (val != null) {
                try {
                    sdk.sendCommand(List.of("RPUSH", currentEditingKey, val));
                    loadKeyDetailsFromServer(currentEditingKey);
                    handleRefreshKeys();
                } catch (Exception ex) {
                    showMsg("操作失败", ex.getMessage(), true);
                }
            }
        } else if ("set".equalsIgnoreCase(currentEditingType)) {
            String val = showInput("加入 Set 成员", "请输入要加入集合的成员值:");
            if (val != null) {
                try {
                    sdk.sendCommand(List.of("SADD", currentEditingKey, val));
                    loadKeyDetailsFromServer(currentEditingKey);
                    handleRefreshKeys();
                } catch (Exception ex) {
                    showMsg("操作失败", ex.getMessage(), true);
                }
            }
        } else if ("hash".equalsIgnoreCase(currentEditingType)) {
            String[] kv = showHashAddDialog();
            if (kv != null) {
                try {
                    sdk.sendCommand(List.of("HSET", currentEditingKey, kv[0], kv[1]));
                    loadKeyDetailsFromServer(currentEditingKey);
                    handleRefreshKeys();
                } catch (Exception ex) {
                    showMsg("操作失败", ex.getMessage(), true);
                }
            }
        }
    }

    private void handleRemoveTableRow() {
        int row = valueTable.getSelectedRow();
        if (row == -1 || currentEditingKey == null || sdk == null) {
            showMsg("移除提示", "请先在数据表格中选中某行。", true);
            return;
        }

        try {
            if ("list".equalsIgnoreCase(currentEditingType)) {
                boolean confirmed = showConfirm("弹出元素", "确定将该元素从双端列表尾部弹出 (RPOP) 吗？");
                if (confirmed) {
                    sdk.sendCommand(List.of("RPOP", currentEditingKey));
                }
            } else if ("set".equalsIgnoreCase(currentEditingType)) {
                String member = valueTableModel.getValueAt(row, 0).toString();
                sdk.sendCommand(List.of("SREM", currentEditingKey, member));
            } else if ("hash".equalsIgnoreCase(currentEditingType)) {
                String field = valueTableModel.getValueAt(row, 0).toString();
                sdk.sendCommand(List.of("HDEL", currentEditingKey, field));
            }
            loadKeyDetailsFromServer(currentEditingKey);
            handleRefreshKeys();
        } catch (Exception ex) {
            showMsg("移除行失败", ex.getMessage(), true);
        }
    }

    // ==========================================
    // 14. Dashboard 动态监控 & Sparkline 画图
    // ==========================================
    private void updateDashboardQps() {
        if (sdk == null) return;
        try {
            Long dbSize = (Long) sdk.sendCommand(List.of("DBSIZE"));
            if (dbSize != null) {
                dbSizeLabel.setText(String.valueOf(dbSize));
            }
            
            long curTotalCommands = 0;
            String roleStr = "STANDALONE";
            // 请求服务端 INFO 信息并解析 used_memory, total_commands_processed, role
            Object infoRes = sdk.sendCommand(List.of("INFO"));
            if (infoRes != null) {
                String infoStr = infoRes.toString();
                String[] lines = infoStr.split("\r\n");
                long usedMem = 0;
                for (String line : lines) {
                    if (line.startsWith("used_memory:")) {
                        usedMem = Long.parseLong(line.substring("used_memory:".length()));
                    } else if (line.startsWith("total_commands_processed:")) {
                        curTotalCommands = Long.parseLong(line.substring("total_commands_processed:".length()));
                    } else if (line.startsWith("role:")) {
                        roleStr = line.substring("role:".length()).trim();
                    }
                }
                if (usedMem > 0) {
                    if (usedMem >= 1024 * 1024) {
                        double mb = (double) usedMem / (1024 * 1024);
                        memoryLabel.setText(String.format(Locale.US, "%.2f MB", mb));
                    } else {
                        double kb = (double) usedMem / 1024;
                        memoryLabel.setText(String.format(Locale.US, "%.2f KB", kb));
                    }
                }
            }
            
            // 动态解析集群节点角色，并在卡片上刷新对应的莫兰迪配色主题样式
            if ("LEADER".equalsIgnoreCase(roleStr)) {
                roleLabel.setText("LEADER (集群主节点)");
                roleCard.setThemeColors(new Color(254, 243, 199), new Color(253, 230, 138)); 
            } else if ("FOLLOWER".equalsIgnoreCase(roleStr)) {
                roleLabel.setText("FOLLOWER (集群从节点)");
                roleCard.setThemeColors(new Color(236, 253, 245), new Color(167, 243, 208)); 
            } else if ("CANDIDATE".equalsIgnoreCase(roleStr)) {
                roleLabel.setText("CANDIDATE (竞选状态)");
                roleCard.setThemeColors(new Color(254, 226, 226), new Color(252, 165, 165)); 
            } else {
                roleLabel.setText("STANDALONE (单机节点)");
                roleCard.setThemeColors(new Color(241, 245, 249), new Color(226, 232, 240)); 
            }

            // 根据命令累计次数计算真实的秒级 QPS 吞吐量
            long qpsVal = 0;
            long now = System.currentTimeMillis();
            if (curTotalCommands > 0) {
                if (prevTotalCommands > 0 && now > prevInfoTime) {
                    double seconds = (double) (now - prevInfoTime) / 1000.0;
                    qpsVal = Math.max(0, Math.round((curTotalCommands - prevTotalCommands) / seconds));
                } else if (prevTotalCommands == 0) {
                    // 第一次连接，初始化计数器
                    qpsVal = 0;
                }
                prevTotalCommands = curTotalCommands;
                prevInfoTime = now;
            }
            
            qpsChart.addDataPoint((int) qpsVal);
            
        } catch (Exception e) {
            qpsTimer.stop();
        }
    }

    // ==========================================
    // 15. O(1) 磁盘 Seek 动画仿真测试
    // ==========================================
    private void handleSeekSimulation() {
        startSeekBtn.setEnabled(false);
        seekConsole.setText("");
        
        new Thread(() -> {
            try {
                String[] keys = {"user:1001", "product:902", "order:3319", "user:1002", "session:token", "config:sys"};
                Random rand = new Random();
                
                for (int i = 0; i < 3; i++) {
                    for (String k : keys) {
                        int progress = 0;
                        seekConsole.append(">> GET " + k + "\n");
                        
                        boolean hit = rand.nextBoolean();
                        if (hit) {
                            seekConsole.append("   [缓存命中] 成功在内存 LRU 缓存中预测命中 (O(1)). 耗时: 0.02ms\n\n");
                            progress = 100;
                            seekProgressBar.setValue(progress);
                            Thread.sleep(300);
                        } else {
                            seekConsole.append("   [缓存未命中] 内存 LRU 缓存未命中，向磁盘存储引擎发起查询...\n");
                            seekProgressBar.setValue(20);
                            Thread.sleep(400);
                            
                            long offset = 120 + rand.nextInt(5000);
                            seekConsole.append("   [O(1) 磁盘定位] 使用内存偏移量索引快速 Seek 磁盘物理位置: " + String.format("0x%04X", offset) + "...\n");
                            seekProgressBar.setValue(60);
                            Thread.sleep(500);
                            
                            seekConsole.append("   [磁盘读取] 从对应数据文件中拉取数据段，重新写入内存 LRU 缓存...\n");
                            seekConsole.append("   [读取成功] 数据成功装载. 耗时: 1.25ms\n\n");
                            seekProgressBar.setValue(100);
                            Thread.sleep(300);
                        }
                    }
                }
                seekConsole.append("=========================================\n");
                seekConsole.append(" 磁盘 O(1) Seek 仿真测试全部完成！\n");
                seekConsole.append("=========================================\n");
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            } finally {
                startSeekBtn.setEnabled(true);
            }
        }).start();
    }

    // ==========================================
    // 16. 控制台 Console 原生命令执行
    // ==========================================
    private void handleConsoleCommand() {
        String rawCmd = consoleInputField.getText().trim();
        if (rawCmd.isEmpty()) return;
        
        consoleOutputArea.append("> " + rawCmd + "\n");
        consoleInputField.setText("");
        
        if (sdk == null) {
            consoleOutputArea.append("错误: 客户端尚未连接至服务端。\n\n");
            return;
        }

        try {
            String[] parts = rawCmd.split("\\s+");
            List<String> cmdList = Arrays.asList(parts);
            
            Object res = sdk.sendCommand(cmdList);
            if (res == null) {
                consoleOutputArea.append("(nil)\n\n");
            } else if (res instanceof List) {
                List<?> list = (List<?>) res;
                for (int i = 0; i < list.size(); i++) {
                    consoleOutputArea.append((i + 1) + ") \"" + list.get(i).toString() + "\"\n");
                }
                consoleOutputArea.append("\n");
            } else {
                consoleOutputArea.append(res.toString() + "\n\n");
            }
            
            String action = parts[0].toUpperCase();
            if (isWriteAction(action)) {
                handleRefreshKeys();
            }
            
        } catch (Exception ex) {
            consoleOutputArea.append("ERR " + ex.getMessage() + "\n\n");
        }
    }

    private boolean isWriteAction(String action) {
        return "SET".equals(action) || "DEL".equals(action) || "HSET".equals(action) || "LPUSH".equals(action) || "RPUSH".equals(action) || "SADD".equals(action);
    }

    public void show() {
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new NoSqlGui().show();
        });
    }

    // ==========================================
    // JTree 节点包装与渲染器
    // ==========================================
    private static class KeyNodeInfo {
        String segment;
        boolean isLeaf;
        String fullKey;

        public KeyNodeInfo(String segment, boolean isLeaf, String fullKey) {
            this.segment = segment;
            this.isLeaf = isLeaf;
            this.fullKey = fullKey;
        }

        @Override
        public String toString() {
            return segment;
        }
    }

    private class KeyTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object val, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, val, sel, exp, leaf, row, focus);
            
            setBackgroundNonSelectionColor(COLOR_CARD);
            setBackgroundSelectionColor(new Color(239, 246, 255));
            setTextNonSelectionColor(COLOR_TEXT);
            setTextSelectionColor(COLOR_ACCENT);
            setBorderSelectionColor(null);
            
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) val;
            Object userObj = node.getUserObject();
            
            if (userObj instanceof KeyNodeInfo) {
                KeyNodeInfo info = (KeyNodeInfo) userObj;
                if (info.isLeaf) {
                    setText(info.segment + "  ");
                    setIcon(null);
                } else {
                    setText(info.segment + "/");
                    setForeground(COLOR_ACCENT);
                }
            }
            
            return this;
        }
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(COLOR_MUTED);
        label.setFont(FONT_BOLD);
        return label;
    }

    private RoundedPanel createStatCard(String title, JLabel valueLabel, Color bg, Color border) {
        RoundedPanel card = new RoundedPanel(16, bg, border); 
        card.setLayout(new BorderLayout(8, 8)); 
        card.setBorder(new EmptyBorder(15, 20, 15, 20)); 
        
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(COLOR_MUTED);
        titleLabel.setFont(FONT_BOLD); 
        
        valueLabel.setForeground(COLOR_TEXT);
        valueLabel.setFont(new Font("Fira Code", Font.BOLD, 28)); 
        
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private void styleTable(JTable table) {
        table.setBackground(COLOR_CARD);
        table.setForeground(COLOR_TEXT);
        table.setFont(FONT_CODE);
        table.setRowHeight(32); 
        table.setShowGrid(true); 
        table.setGridColor(new Color(226, 232, 240));
        table.setSelectionBackground(new Color(239, 246, 255));
        table.setSelectionForeground(COLOR_ACCENT);

        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(241, 245, 249));
        header.setForeground(COLOR_TEXT);
        header.setFont(FONT_BOLD);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(203, 213, 225)));

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean isSel, boolean hasFocus, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, val, isSel, hasFocus, r, c);
                if (!isSel) {
                    comp.setBackground(r % 2 == 0 ? COLOR_CARD : new Color(248, 250, 252));
                }
                setBorder(new EmptyBorder(0, 8, 0, 8)); 
                return comp;
            }
        };
        renderer.setHorizontalAlignment(SwingConstants.LEFT);
        table.setDefaultRenderer(Object.class, renderer);
    }
}

// ==========================================
// 自定义 Swing 扁平简洁与交互反馈 UI 组件
// ==========================================

class RoundedPanel extends javax.swing.JPanel {
    private final int cornerRadius;
    private java.awt.Color backgroundColor;
    private java.awt.Color borderColor = null;

    public RoundedPanel(int radius, java.awt.Color bg) {
        this.cornerRadius = radius;
        this.backgroundColor = bg;
        setOpaque(false);
    }

    public RoundedPanel(int radius, java.awt.Color bg, java.awt.Color border) {
        this.cornerRadius = radius;
        this.backgroundColor = bg;
        this.borderColor = border;
        setOpaque(false);
    }

    public void setThemeColors(java.awt.Color bg, java.awt.Color border) {
        this.backgroundColor = bg;
        this.borderColor = border;
        repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        java.awt.Graphics2D graphics = (java.awt.Graphics2D) g;
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        
        graphics.setColor(backgroundColor);
        graphics.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, cornerRadius, cornerRadius);
        
        if (borderColor != null) {
            graphics.setColor(borderColor);
            graphics.setStroke(new java.awt.BasicStroke(1f));
            graphics.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, cornerRadius, cornerRadius);
        }
    }
}

class RoundedBorder implements javax.swing.border.Border {
    private final int radius;
    private final Color color;

    public RoundedBorder(int radius, Color color) {
        this.radius = radius;
        this.color = color;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(this.radius / 2, this.radius / 2, this.radius / 2, this.radius / 2);
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
        g2.dispose();
    }
}

class FlatTextField extends javax.swing.JTextField {
    private final int radius = 12; 
    public FlatTextField(String text, int columns) {
        super(text, columns);
        setBackground(java.awt.Color.WHITE);
        setForeground(new java.awt.Color(15, 23, 42));
        setCaretColor(new java.awt.Color(15, 23, 42));
        setFont(new java.awt.Font("Fira Code", java.awt.Font.PLAIN, 15)); 
        setOpaque(false); 
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 14, 8, 14)); 
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(getBackground());
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        
        g2.setColor(new java.awt.Color(226, 232, 240)); 
        g2.setStroke(new java.awt.BasicStroke(1f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        super.paintComponent(g2);
        g2.dispose();
    }
}

class FlatButton extends javax.swing.JButton {
    private java.awt.Color bgNormal = new java.awt.Color(241, 245, 249);
    private java.awt.Color bgHover = new java.awt.Color(226, 232, 240);
    private java.awt.Color bgPressed = new java.awt.Color(203, 213, 225);
    private java.awt.Color fgColor = new java.awt.Color(15, 23, 42);
    private final int radius = 14; 

    public FlatButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setForeground(fgColor);
        setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.BOLD, 14)); 
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (isEnabled()) {
                    setBackground(bgHover);
                }
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (isEnabled()) {
                    setBackground(bgNormal);
                }
            }
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (isEnabled()) {
                    setBackground(bgPressed);
                }
            }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (isEnabled()) {
                    if (getVisibleRect().contains(e.getPoint())) {
                        setBackground(bgHover);
                    } else {
                        setBackground(bgNormal);
                    }
                }
            }
        });
        setBackground(bgNormal);
    }

    public void setThemeColors(java.awt.Color normal, java.awt.Color hover, java.awt.Color pressed, java.awt.Color fg) {
        this.bgNormal = normal;
        this.bgHover = hover;
        this.bgPressed = pressed;
        this.fgColor = fg;
        setForeground(fg);
        setBackground(normal);
        repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        
        if (!isEnabled()) {
            g2.setColor(new java.awt.Color(241, 245, 249));
            setForeground(new java.awt.Color(156, 163, 175));
        } else {
            g2.setColor(getBackground());
            setForeground(fgColor);
        }
        
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        
        super.paintComponent(g2);
        g2.dispose();
    }
}

// QPS 实时监测渐变曲线绘制板
class QpsChartPanel extends javax.swing.JPanel {
    private final java.util.List<Integer> qpsHistory = new java.util.ArrayList<>();
    private static final int MAX_HISTORY_SIZE = 30;

    public QpsChartPanel() {
        setBackground(java.awt.Color.WHITE);
        setPreferredSize(new java.awt.Dimension(0, 150));
    }

    public synchronized void addDataPoint(int qps) {
        qpsHistory.add(qps);
        if (qpsHistory.size() > MAX_HISTORY_SIZE) {
            qpsHistory.remove(0);
        }
        repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // 1. 定义坐标绘制区域的边距 (Padding) 为轴线与刻度预留位置
        int paddingLeft = 65;
        int paddingBottom = 40;
        int paddingTop = 35;
        int paddingRight = 35;

        int chartWidth = w - paddingLeft - paddingRight;
        int chartHeight = h - paddingTop - paddingBottom;

        if (chartWidth <= 0 || chartHeight <= 0) return;

        // 2. 动态获取历史 QPS 最大值
        int max = 10;
        for (int val : qpsHistory) {
            if (val > max) max = val;
        }
        // 向上取整到偶数，图表刻度线更整齐
        max = ((max + 1) / 2) * 2;

        // 3. 绘制横向网格背景线与 Y 轴刻度数值 (以及 Y 轴上的物理小刻度短线)
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        int yTicks = 4;
        for (int i = 0; i <= yTicks; i++) {
            int yVal = max * i / yTicks;
            int y = paddingTop + chartHeight - (yVal * chartHeight / max);
            
            // 绘制横向网格线 (淡灰)
            g2.setColor(new Color(241, 245, 249));
            g2.drawLine(paddingLeft, y, w - paddingRight, y);
            
            // 绘制 Y 轴物理刻度小短线 (向左突出 4 像素)
            g2.setColor(new Color(203, 213, 225));
            g2.drawLine(paddingLeft - 4, y, paddingLeft, y);
            
            // 绘制 Y 轴数值刻度文字
            g2.setColor(new Color(100, 116, 139)); // Muted text
            g2.drawString(String.valueOf(yVal), paddingLeft - 35, y + 5);
        }

        // 4. 绘制 X 轴时间刻度数值 (跟随真实系统时间 HH:mm:ss 每秒滚动变化，以及 X 轴物理刻度线)
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        long nowMs = System.currentTimeMillis();
        String timeNow = sdf.format(new java.util.Date(nowMs));
        String timeMinus15 = sdf.format(new java.util.Date(nowMs - 15000));
        String timeMinus30 = sdf.format(new java.util.Date(nowMs - 30000));

        g2.setColor(new Color(100, 116, 139));
        
        // -30s 时间点
        g2.drawString(timeMinus30, paddingLeft - 22, h - paddingBottom + 20);
        g2.setColor(new Color(203, 213, 225));
        g2.drawLine(paddingLeft, h - paddingBottom, paddingLeft, h - paddingBottom + 4);
        
        // -15s 时间点
        g2.setColor(new Color(100, 116, 139));
        g2.drawString(timeMinus15, paddingLeft + chartWidth / 2 - 25, h - paddingBottom + 20);
        g2.setColor(new Color(203, 213, 225));
        g2.drawLine(paddingLeft + chartWidth / 2, h - paddingBottom, paddingLeft + chartWidth / 2, h - paddingBottom + 4);
        
        // 0s 当前时间点
        g2.setColor(new Color(100, 116, 139));
        g2.drawString(timeNow + " (当前)", w - paddingRight - 65, h - paddingBottom + 20);
        g2.setColor(new Color(203, 213, 225));
        g2.drawLine(w - paddingRight, h - paddingBottom, w - paddingRight, h - paddingBottom + 4);

        // 5. 绘制 X 轴 & Y 轴的数据刻度单位
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        g2.setColor(new Color(15, 23, 42)); // Slate 900
        // Y 轴单位：QPS (次/秒) - 摆在 Y 轴顶端
        g2.drawString("吞吐率 (QPS)", paddingLeft - 60, paddingTop - 15);
        // X 轴单位：时间 (秒) - 摆在 X 轴最右侧下方
        g2.drawString("时间 (秒 / s)", w - paddingRight - 20, h - paddingBottom + 35);

        // 6. 绘制坐标轴基准线 (Slate 300)
        g2.setColor(new Color(203, 213, 225));
        g2.setStroke(new java.awt.BasicStroke(1.5f));
        // X 轴基线
        g2.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom);
        // Y 轴基线
        g2.drawLine(paddingLeft, paddingTop, paddingLeft, h - paddingBottom);

        // 7. 绘制折线与渐变填充阴影
        if (qpsHistory.size() < 2) return;

        g2.setStroke(new java.awt.BasicStroke(2.5f));
        g2.setColor(new Color(37, 99, 235)); // 蔚蓝折线

        int size = qpsHistory.size();
        int[] xPoints = new int[size];
        int[] yPoints = new int[size];

        int step = chartWidth / (MAX_HISTORY_SIZE - 1);
        for (int i = 0; i < size; i++) {
            xPoints[i] = paddingLeft + i * step;
            yPoints[i] = paddingTop + chartHeight - (qpsHistory.get(i) * chartHeight / max);
        }

        // 绘制主折线
        g2.drawPolyline(xPoints, yPoints, size);

        // 绘制阴影渐变填充
        g2.setStroke(new java.awt.BasicStroke(1));
        java.awt.GradientPaint paint = new java.awt.GradientPaint(0, paddingTop, new java.awt.Color(37, 99, 235, 45), 0, h - paddingBottom, new java.awt.Color(37, 99, 235, 0));
        g2.setPaint(paint);

        java.awt.Polygon polygon = new java.awt.Polygon();
        polygon.addPoint(paddingLeft, h - paddingBottom);
        for (int i = 0; i < size; i++) {
            polygon.addPoint(xPoints[i], yPoints[i]);
        }
        polygon.addPoint(xPoints[size - 1], h - paddingBottom);
        g2.fill(polygon);
    }
}
