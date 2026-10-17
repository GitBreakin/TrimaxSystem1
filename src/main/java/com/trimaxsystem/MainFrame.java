package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;

public class MainFrame extends JFrame {

    private JPanel mainContentPanel;
    private CardLayout cardLayout;

    public MainFrame() {
        setTitle("TRI-MAX Goods Trading Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLayout(new BorderLayout());
        setResizable(false);

        // --- SIDEBAR PANEL (WEST) ---
        JPanel sidebarPanel = new JPanel();
        sidebarPanel.setBackground(Color.decode("#6C63FF"));
        sidebarPanel.setPreferredSize(new Dimension(220, 0));
        sidebarPanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.insets = new Insets(12, 15, 12, 15);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        String[] buttonLabels = {
                "Dashboard",
                "Inventory",
                "Bookings",
                "Sales Agents",
                "Clients",
                "Drivers and Vehicles"
        };

        // --- MAIN CONTENT PANEL (CENTER) ---
        cardLayout = new CardLayout();
        mainContentPanel = new JPanel(cardLayout);

        // Add custom panels
        mainContentPanel.add(new DashboardPanel(), "Dashboard");
        mainContentPanel.add(new InventoryPanel(), "Inventory"); // <--- Connected Inventory Panel
        mainContentPanel.add(new BookingsPanel(), "Bookings");
        mainContentPanel.add(new SalesAgentsPanel(), "Sales Agents");
        mainContentPanel.add(new ClientsPanel(), "Clients");
        mainContentPanel.add(new DriversNVehiclesPanel(), "Drivers and Vehicles");

        // Create sidebar buttons
        for (String text : buttonLabels) {
            JButton btn = new JButton(text);
            btn.setPreferredSize(new Dimension(180, 45));
            btn.setFocusable(false);

            btn.addActionListener(e -> cardLayout.show(mainContentPanel, text));
            sidebarPanel.add(btn, gbc);
        }

        add(sidebarPanel, BorderLayout.WEST);
        add(mainContentPanel, BorderLayout.CENTER);

        setVisible(true);
    }

    private JPanel createDummyPanel(String text) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.decode("#DCDCDC"));
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 24));
        panel.add(label);
        return panel;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame());
    }
}