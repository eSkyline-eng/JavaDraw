import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.awt.Color;

import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::createAndShowGUI);
    }

    private static DrawPanel canvas;
    private static TokenRegistry REG;
    private static TokenRegistry.EncodingConfig CFG;

    static class DrawPanel extends JPanel {
        java.util.List<Body> bodies = new java.util.ArrayList<>();

        double xMin = -1.0, xMax = 1.0;
        double yMin = -1.0, yMax = 1.0;

        void setBodies(java.util.List<Body> bodies) {
            this.bodies = bodies;
            repaint();
        }

        java.util.List<Body> getBodies() {
            return bodies;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            // background
            g2.setColor(new Color(10, 0, 60));
            g2.fillRect(0, 0, getWidth(), getHeight());

            for (Body b : bodies) {
                int cx = worldToPxX(b.x);
                int cy = worldToPxY(b.y);
                int r  = b.r;

                g2.setColor(b.color);
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
        }

        int worldToPxX(double x) {
            return (int) Math.round((x - xMin) / (xMax - xMin) * getWidth());
        }
        int worldToPxY(double y) {
            return (int) Math.round((1.0 - (y - yMin) / (yMax - yMin)) * getHeight());
        }
    }

    private static void createAndShowGUI() {
        canvas = new DrawPanel();

        // Define encoding schemes
        CFG = new TokenRegistry.EncodingConfig(100,199, 200,299, 300,699, 700,999);
        REG = new TokenRegistry(CFG);
        REG.registerKeywords("body","remove","clear","if","while");
        REG.registerOperators("+","-","==","=","[");


        // ===== Window =====
        JFrame frame = new JFrame("JavaDraw");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1600, 900);
        frame.setLocationRelativeTo(null);

        // ===== Main vertical stack =====
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ===== Top row: INPUT (left) | CANVAS (right) =====
        JPanel topRow = new JPanel();
        topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Let the row grow; remove the 300px cap
        topRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // -- INPUT (left) in a scroll pane --
        JTextArea inputArea = new JTextArea();
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JScrollPane inputScroll = new JScrollPane(inputArea);
        final int LEFT_WIDTH = 520;
        inputScroll.setPreferredSize(new Dimension(LEFT_WIDTH, 700));
        inputScroll.setMaximumSize(new Dimension(LEFT_WIDTH, Integer.MAX_VALUE));
        inputScroll.setMinimumSize(new Dimension(LEFT_WIDTH, 200));

        // -- CANVAS --
        canvas.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        canvas.setPreferredSize(new Dimension(1000, 700));
        canvas.setMinimumSize(new Dimension(400, 300));

        // Assemble top row
        topRow.add(inputScroll);
        topRow.add(Box.createRigidArea(new Dimension(10, 0)));
        topRow.add(canvas);

        // ===== OUTPUT LOG =====
        JTextArea outputBox1 = new JTextArea();
        outputBox1.setEditable(false);
        outputBox1.setLineWrap(true);
        outputBox1.setWrapStyleWord(true);

        JScrollPane outScroll = new JScrollPane(outputBox1);
        outScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        outScroll.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        outScroll.setPreferredSize(new Dimension(1200, 140));
        outScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        // ===== Run button =====
        JButton runButton = new JButton("Run");
        runButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        runButton.setMaximumSize(new Dimension(100, 30));

        // ===== Compose =====
        mainPanel.add(topRow);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(outScroll);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(runButton);

        frame.getContentPane().add(mainPanel);
        frame.setVisible(true);

        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                outputBox1.setText("");

                String inputText = inputArea.getText();
                String[] lines = inputText.split("\\R", -1);

                java.util.List<Body> bodies = new ArrayList<>(canvas.getBodies());

                int lineNum = 0;
                for (String line : lines) {
                    lineNum++;

                    outputBox1.append(lineNum + " >  " + tokenizeLine(line) + "\n");

                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;

                    String[] tok = trimmed.split("\\s+");
                    // Remove later for clean output
                    outputBox1.append("      enc: " + formatEncodedTokens(tok) + "\n");
                    try {
                        if (tok[0].equalsIgnoreCase("body")) {
                            Body b = parseBodyLine(tok);
                            bodies.add(b);
                        } else if (tok[0].equalsIgnoreCase("help")){
                            outputBox1.append("List of commands: \n" + "    body <double x> <double y> <doublev vx> <double vy> <int radiusPx> <int mass> <color>");
                        } else if (tok[0].equalsIgnoreCase("remove")) {
                            parseRemoveLine(tok, bodies, outputBox1);
                        } else if (tok[0].equalsIgnoreCase("clear")) {
                            bodies.clear();
                            outputBox1.append("    * Cleared\n");
                        } else {
                            outputBox1.append("!   Not a command\n");
                        }
                    } catch (IllegalArgumentException ex) {
                        outputBox1.append("     ! Parse Error " + ex.getMessage() + "\n");
                    }
                }
                canvas.setBodies(bodies);
            }
        });

    }

    private static String tokenizeLine(String line) {
        String s = line;

        s = s.replaceAll("(==|!=|<=|>=|px)", " $1 ");

        s = s.replaceAll("([(){}\\[\\],;:+\\-*/%<>=!])", " $1 ");

        String[] parts = Arrays.stream(s.trim().split("\\s+"))
                .filter(tok -> !tok.isEmpty())
                .toArray(String[]::new);

        if (parts.length == 0) return "EOL";
        return String.join(", ", parts) + ", EOL";
    }

    // Will be removed for cleaner output
    // Helper for tokenizeLine()
    private static String formatEncodedTokens(String[] tokens) {
        if (tokens == null || tokens.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            TokenRegistry.EncodedToken et = REG.encode(t);
            sb.append(et.lexeme).append("(").append(et.code).append(") ");
        }
        return sb.toString().trim();
    }


    // Parse: [body?] <name> <x> <y> <mass> <radiusPx> <color>
    static Body parseBodyLine(String[] tok) {
        if (tok.length < 9) {
            throw new IllegalArgumentException(
                    "Expected: body <name> <x> <y> <vx> <vy> <radiusPx> <mass> <color>");
        }
        if (!tok[0].equalsIgnoreCase("body")) {
            throw new IllegalArgumentException("Line must start with 'body'");
        }

        String name = tok[1];
        double x = parseDouble(tok[2]);
        double y = parseDouble(tok[3]);
        double xv = parseDouble(tok[4]);
        double yv = parseDouble(tok[5]);
        int r = parseInt(tok[6]);
        double mass = parseDouble(tok[7]);
        String color = tok[8];

        if (r <= 0) throw new IllegalArgumentException("Radius at or below 0");

        Body b = new Body(name);
        b.x = x;
        b.y = y;
        b.mass = mass;
        b.r = r;
        b.xv = xv;
        b.yv = yv;
        b.color = parseColor(color);

        return b;
    }

    static Color parseColor(String c) {
        try {
            if (c.startsWith("#")) {
                int rgb = Integer.parseInt(c.substring(1), 16);
                return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
            }
            switch (c.toLowerCase()) {
                case "red": return Color.RED;
                case "green": return Color.GREEN;
                case "blue": return Color.BLUE;
                case "yellow": return Color.YELLOW;
                case "orange": return Color.ORANGE;
                case "white": return Color.WHITE;
            }
        } catch (Exception e) {
            return Color.WHITE;
        }
        return Color.WHITE;
    }

    static void parseRemoveLine (String[] tok, java.util.List<Body> bodies, JTextArea outputArea) {
        if (tok.length != 2) {
            outputArea.append("!    Incorrect usage: remove <name>\n");
            return;
        }
        String name = tok[1];
        boolean ok = bodies.removeIf(b -> name.equals(b.name));
        if (!ok) {
            outputArea.append("!    Failed to remove " + tok[1] + "\n");
        }
    }
}
