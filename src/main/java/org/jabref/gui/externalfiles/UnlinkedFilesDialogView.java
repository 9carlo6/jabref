package org.jabref.gui.externalfiles;

import javax.inject.Inject;
import javax.swing.undo.UndoManager;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import org.jabref.gui.DialogService;
import org.jabref.gui.StateManager;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.icon.IconTheme;
import org.jabref.gui.icon.JabRefIcon;
import org.jabref.gui.texparser.FileNodeViewModel;
import org.jabref.gui.util.BaseDialog;
import org.jabref.gui.util.ControlHelper;
import org.jabref.gui.util.IconValidationDecorator;
import org.jabref.gui.util.RecursiveTreeItem;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.gui.util.ValueTableCellFactory;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.gui.util.ViewModelTreeCellFactory;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.util.FileUpdateMonitor;
import org.jabref.preferences.PreferencesService;

import com.airhacks.afterburner.views.ViewLoader;
import com.tobiasdiez.easybind.EasyBind;
import de.saxsys.mvvmfx.utils.validation.visualization.ControlsFxVisualizer;
import org.controlsfx.control.CheckTreeView;

public class UnlinkedFilesDialogView extends BaseDialog<Void> {

    @FXML private TextField directoryPathField;
    @FXML private ComboBox<FileExtensionViewModel> fileTypeSelection;
    @FXML private CheckTreeView<FileNodeViewModel> tree;
    @FXML private Button buttonScan;
    @FXML private ButtonType importButton;
    @FXML private Button buttonExport;
    @FXML private Label progressText;
    @FXML private Accordion accordion;
    @FXML private ProgressIndicator progressDisplay;
    @FXML private VBox progressPane;

    @FXML private TableView<ImportFilesResultItemViewModel> tvResult;
    @FXML private TableColumn<ImportFilesResultItemViewModel, JabRefIcon> colStatus;
    @FXML private TableColumn<ImportFilesResultItemViewModel, String> colMessage;
    @FXML private TableColumn<ImportFilesResultItemViewModel, String> colFile;

    @FXML private TitledPane filePane;
    @FXML private TitledPane resultPane;

    @Inject private PreferencesService preferencesService;
    @Inject private DialogService dialogService;
    @Inject private StateManager stateManager;
    @Inject private UndoManager undoManager;
    @Inject private TaskExecutor taskExecutor;
    @Inject private FileUpdateMonitor fileUpdateMonitor;

    private UnlinkedFilesDialogViewModel viewModel;
    private ControlsFxVisualizer validationVisualizer;

    public UnlinkedFilesDialogView() {
        this.validationVisualizer = new ControlsFxVisualizer();

        this.setTitle(Localization.lang("Search for unlinked local files"));

        ViewLoader.view(this)
                  .load()
                  .setAsDialogPane(this);

        Button btnImport = (Button) this.getDialogPane().lookupButton(importButton);
        ControlHelper.setAction(importButton, getDialogPane(), evt -> viewModel.startImport());
        btnImport.disableProperty().bindBidirectional(viewModel.applyButtonDisabled());
        btnImport.setTooltip(new Tooltip(Localization.lang("Starts the import of BibTeX entries.")));

        setResultConverter(button -> {
            if (button == ButtonType.CANCEL) {
                viewModel.cancelTasks();
            }
            return null;
        });
    }

    @FXML
    private void initialize() {
        viewModel = new UnlinkedFilesDialogViewModel(dialogService, ExternalFileTypes.getInstance(), undoManager, fileUpdateMonitor, preferencesService, stateManager, taskExecutor);
        viewModel.directoryPath().bindBidirectional(directoryPathField.textProperty());

        validationVisualizer.setDecoration(new IconValidationDecorator());
        validationVisualizer.initVisualization(viewModel.directoryPathValidator(), directoryPathField);

        fileTypeSelection.setItems(viewModel.getFileFilters());

        new ViewModelListCellFactory<FileExtensionViewModel>()
              .withText(fileFilter -> fileFilter.getDescription())
              .withIcon(fileFilter -> fileFilter.getIcon())
              .install(fileTypeSelection);

        tree.rootProperty().bind(EasyBind.map(viewModel.treeRoot(), fileNode -> new RecursiveTreeItem<>(fileNode, FileNodeViewModel::getChildren)));

        new ViewModelTreeCellFactory<FileNodeViewModel>()
             .withText(FileNodeViewModel::getDisplayText)
             .install(tree);

        EasyBind.subscribe(tree.rootProperty(), root -> {
            ((CheckBoxTreeItem<FileNodeViewModel>) root).setSelected(true);
            root.setExpanded(true);
            EasyBind.bindContent(viewModel.getCheckedFileList(), tree.getCheckModel().getCheckedItems());
        });
        tree.setPrefWidth(Double.POSITIVE_INFINITY);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        viewModel.scanButtonDisabled().bindBidirectional(buttonScan.disableProperty());
        viewModel.scanButtonDefaultButton().bindBidirectional(buttonScan.defaultButtonProperty());
        viewModel.exportButtonDisabled().bindBidirectional(buttonExport.disableProperty());
        viewModel.selectedExtension().bind(fileTypeSelection.valueProperty());

        tvResult.setItems(viewModel.resultTableItems());

        progressDisplay.progressProperty().bind(viewModel.progress());
        progressText.textProperty().bind(viewModel.progressText());

        progressPane.managedProperty().bind(viewModel.searchProgressVisible());
        progressPane.visibleProperty().bind(viewModel.searchProgressVisible());
        accordion.disableProperty().bind(viewModel.searchProgressVisible());
        tree.maxHeightProperty().bind(((Control) filePane.contentProperty().get()).heightProperty());

        viewModel.filePaneExpanded().bindBidirectional(filePane.expandedProperty());
        viewModel.resultPaneExpanded().bindBidirectional(resultPane.expandedProperty());

        viewModel.scanButtonDefaultButton().setValue(true);
        viewModel.scanButtonDisabled().setValue(true);
        viewModel.applyButtonDisabled().setValue(true);
        fileTypeSelection.getSelectionModel().selectFirst();

        setupResultTable();
    }

    private void setupResultTable() {
        colFile.setCellValueFactory(cellData -> cellData.getValue().file());
        new ValueTableCellFactory<ImportFilesResultItemViewModel, String>()
                                                                           .withText(item -> item).withTooltip(item -> item)
                                                                           .install(colFile);

        colMessage.setCellValueFactory(cellData -> cellData.getValue().message());
        new ValueTableCellFactory<ImportFilesResultItemViewModel, String>()
                                                                           .withText(item -> item).withTooltip(item -> item)
                                                                           .install(colMessage);

        colStatus.setCellValueFactory(cellData -> cellData.getValue().getIcon());
        colStatus.setCellFactory(new ValueTableCellFactory<ImportFilesResultItemViewModel, JabRefIcon>().withGraphic(this::getIcon));
        tvResult.setColumnResizePolicy((param) -> true);
    }

    @FXML
    void browseFileDirectory(ActionEvent event) {
        viewModel.browseFileDirectory();
    }

    @FXML
    void collapseAll(ActionEvent event) {
        viewModel.collapseAll();
    }

    @FXML
    void expandAll(ActionEvent event) {
        viewModel.expandAll();
    }

    @FXML
    void scanFiles(ActionEvent event) {
        viewModel.startSearch();
    }

    @FXML
    void selectAll(ActionEvent event) {
        viewModel.selectAll();
    }

    @FXML
    void unselectAll(ActionEvent event) {
        viewModel.unselectAll();
    }

    @FXML
    void exportSelected(ActionEvent event) {
        viewModel.startExport();
    }

    private Node getIcon(JabRefIcon icon) {
        if (icon == IconTheme.JabRefIcons.CHECK) {
            icon = icon.withColor(Color.GREEN);
        }
        if (icon == IconTheme.JabRefIcons.WARNING) {
            icon = icon.withColor(Color.RED);
        }
        return icon.getGraphicNode();
    }

}