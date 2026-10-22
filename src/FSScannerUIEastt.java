import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Child component for East / Right Split Workspace:
 * Renders the session captures ledger table (fs-data.bin) and row management buttons.
 */
public final class FSScannerUIEastt extends JPanel {

    private static final DateTimeFormatter TABLE_TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    final SessionTableModel tableModel = new SessionTableModel();
    final JTable sessionTable = new JTable(tableModel);
    final JButton deleteRowBtn = new JButton("Delete Selected");
    final JButton clearLedgerBtn = new JButton("Clear Session");

    public FSScannerUIEastt() {
        super(new BorderLayout(0, 8));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(226, 232, 240), 1, true),
                new EmptyBorder(10, 10, 10, 10)));

        JPanel ledgerTop = new JPanel(new BorderLayout());
        ledgerTop.setOpaque(false);
        JLabel ledgerTitle = new JLabel("Session Captures (fs-data.bin)");
        ledgerTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        ledgerTop.add(ledgerTitle, BorderLayout.WEST);

        JPanel ledgerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        ledgerBtns.setOpaque(false);
        ledgerBtns.add(deleteRowBtn);
        ledgerBtns.add(clearLedgerBtn);
        ledgerTop.add(ledgerBtns, BorderLayout.EAST);

        sessionTable.setRowHeight(26);
        sessionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionTable.setFillsViewportHeight(true);

        add(ledgerTop, BorderLayout.NORTH);
        add(new JScrollPane(sessionTable), BorderLayout.CENTER);
    }

    public FSScannerData.ScanRecord getSelectedRecord() {
        int row = sessionTable.getSelectedRow();
        return row < 0 ? null : tableModel.getRecordAt(row);
    }

    static final class SessionTableModel extends AbstractTableModel {
        private static final String[] COLS = {"File Name", "Role", "DPI", "Page", "Time", "State"};
        private List<FSScannerData.ScanRecord> rows = List.of();

        void setRecords(List<FSScannerData.ScanRecord> records) {
            this.rows = List.copyOf(records);
            fireTableDataChanged();
        }

        FSScannerData.ScanRecord getRecordAt(int row) {
            return (row >= 0 && row < rows.size()) ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FSScannerData.ScanRecord r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> r.name();
                case 1 -> FSScannerData.scanType(r.name());
                case 2 -> r.dpi() + " DPI";
                case 3 -> r.pageType();
                case 4 -> LocalDateTime.ofInstant(r.timestamp(), ZoneId.systemDefault()).format(TABLE_TS);
                case 5 -> r.processed() ? "Processed" : "Pending";
                default -> "";
            };
        }
    }
}