/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.navigator;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.action.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.MenuListener;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.services.IServiceLocator;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.navigator.*;
import org.jkiss.dbeaver.model.navigator.meta.DBXTreeNodeHandler;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectFilter;
import org.jkiss.dbeaver.model.struct.DBSObjectSelector;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceSQL;
import org.jkiss.dbeaver.ui.ActionUtils;
import org.jkiss.dbeaver.ui.IActionConstants;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.ViewerColumnController;
import org.jkiss.dbeaver.ui.dnd.DatabaseObjectTransfer;
import org.jkiss.dbeaver.ui.dnd.TreeNodeTransfer;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerRefresh;
import org.jkiss.dbeaver.ui.navigator.database.DatabaseNavigatorView;
import org.jkiss.dbeaver.ui.navigator.database.NavigatorViewBase;
import org.jkiss.dbeaver.ui.navigator.project.ProjectNavigatorView;
import org.jkiss.utils.CommonUtils;

import java.util.*;
import java.util.List;

/**
 * Navigator utils
 */
public class NavigatorUtils {

    private static final Log log = Log.getLog(NavigatorUtils.class);

    public static DBNNode getSelectedNode(ISelectionProvider selectionProvider)
    {
        if (selectionProvider == null) {
            return null;
        }
        return getSelectedNode(selectionProvider.getSelection());
    }

    public static DBNNode getSelectedNode(ISelection selection)
    {
        if (selection.isEmpty()) {
            return null;
        }
        if (selection instanceof IStructuredSelection) {
            Object selectedObject = ((IStructuredSelection)selection).getFirstElement();
            if (selectedObject instanceof DBNNode) {
                return (DBNNode) selectedObject;
            }
        }
        return null;
    }

    /**
     * Find selected node for specified UI element
     * @param element ui element
     * @return ndoe or null
     */
    public static DBNNode getSelectedNode(UIElement element)
    {
        ISelectionProvider selectionProvider = UIUtils.getSelectionProvider(element.getServiceLocator());
        if (selectionProvider != null) {
            return NavigatorUtils.getSelectedNode(selectionProvider);
        } else {
            return null;
        }
    }

    public static DBSObject getSelectedObject(ISelection selection)
    {
        if (selection.isEmpty() || !(selection instanceof IStructuredSelection)) {
            return null;
        }
        return DBUtils.getFromObject(((IStructuredSelection)selection).getFirstElement());
    }

    public static List<DBSObject> getSelectedObjects(ISelection selection)
    {
        if (selection.isEmpty()) {
            return Collections.emptyList();
        }
        List<DBSObject> result = new ArrayList<>();
        if (selection instanceof IStructuredSelection) {
            for (Iterator iter = ((IStructuredSelection)selection).iterator(); iter.hasNext(); ) {
                DBSObject selectedObject = DBUtils.getFromObject(iter.next());
                if (selectedObject != null) {
                    result.add(selectedObject);
                }
            }
        }
        return result;
    }

    public static void addContextMenu(final IWorkbenchSite workbenchSite, final Viewer viewer)
    {
        addContextMenu(workbenchSite, viewer, null);
    }

    public static void addContextMenu(final IWorkbenchSite workbenchSite, final Viewer viewer, final IMenuListener menuListener)
    {
        MenuManager menuMgr = createContextMenu(workbenchSite, viewer, menuListener);
        if (workbenchSite instanceof IWorkbenchPartSite) {
            ((IWorkbenchPartSite)workbenchSite).registerContextMenu(menuMgr, viewer);
        } else if (workbenchSite instanceof IPageSite) {
            ((IPageSite)workbenchSite).registerContextMenu("navigatorMenu", menuMgr, viewer);
        }
    }

    public static MenuManager createContextMenu(final IWorkbenchSite workbenchSite, final Viewer viewer, final IMenuListener menuListener)
    {
        final Control control = viewer.getControl();
        final MenuManager menuMgr = new MenuManager();
        Menu menu = menuMgr.createContextMenu(control);
        menu.addMenuListener(new MenuListener()
        {
            @Override
            public void menuHidden(MenuEvent e)
            {
            }

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu m = (Menu)e.widget;
                DBNNode node = getSelectedNode(viewer.getSelection());
                if (node != null && !node.isLocked() && node.allowsOpen()) {
                    String commandID = NavigatorUtils.getNodeActionCommand(DBXTreeNodeHandler.Action.open, node, NavigatorCommands.CMD_OBJECT_OPEN);
                    // Dirty hack
                    // Get contribution item from menu item and check it's ID
                    for (MenuItem item : m.getItems()) {
                        Object itemData = item.getData();
                        if (itemData instanceof IContributionItem) {
                            String contribId = ((IContributionItem)itemData).getId();
                            if (contribId != null && contribId.equals(commandID)) {
                                m.setDefaultItem(item);
                            }
                        }
                    }
                }
            }
        });
        menuMgr.addMenuListener(manager -> {
            ViewerColumnController columnController = ViewerColumnController.getFromControl(control);
            if (columnController != null && columnController.isClickOnHeader()) {
                columnController.fillConfigMenu(manager);
                manager.add(new Separator());
                return;
            }

            manager.add(new Separator());

            final IStructuredSelection selection = (IStructuredSelection)viewer.getSelection();
            final DBNNode selectedNode = getSelectedNode(viewer);
            if (selectedNode != null && !selectedNode.isLocked() && workbenchSite != null) {
                // Add "Set active object" menu
                if (selectedNode.isPersisted() && selectedNode instanceof DBNDatabaseNode && !(selectedNode instanceof DBNDatabaseFolder) && ((DBNDatabaseNode)selectedNode).getObject() != null) {
                    final DBSObjectSelector activeContainer = DBUtils.getParentAdapter(
                        DBSObjectSelector.class, ((DBNDatabaseNode) selectedNode).getObject());
                    if (activeContainer != null && activeContainer.supportsDefaultChange()) {
                        DBSObject activeChild;
                        activeChild = activeContainer.getDefaultObject();
                        if (activeChild != ((DBNDatabaseNode)selectedNode).getObject()) {
                            DBNDatabaseNode databaseNode = (DBNDatabaseNode)selectedNode;
                            if (databaseNode.getObject() != null && (activeChild == null || activeChild.getClass() == databaseNode.getObject().getClass())) {
                                manager.add(ActionUtils.makeCommandContribution(workbenchSite, NavigatorCommands.CMD_OBJECT_SET_ACTIVE));
                            }
                        }
                    }
                }

                manager.add(new Separator());
            }

            manager.add(new GroupMarker(NavigatorCommands.GROUP_NAVIGATOR_ADDITIONS));

            manager.add(new GroupMarker(NavigatorCommands.GROUP_TOOLS));
            manager.add(new GroupMarker(NavigatorCommands.GROUP_TOOLS_END));

            manager.add(new GroupMarker(NavigatorCommands.GROUP_NAVIGATOR_ADDITIONS_END));
            manager.add(new GroupMarker(IActionConstants.MB_ADDITIONS_END));

            if (selectedNode != null && !selectedNode.isLocked() && workbenchSite != null) {
                manager.add(new Separator());
                // Add properties button
                if (PreferencesUtil.hasPropertiesContributors(selection.getFirstElement()) && selection.getFirstElement() instanceof DBNResource) {
                    manager.add(ActionUtils.makeCommandContribution(workbenchSite, IWorkbenchCommandConstants.FILE_PROPERTIES));
                }

                if (selectedNode.isPersisted()) {
                    // Add refresh button
                    manager.add(ActionUtils.makeCommandContribution(workbenchSite, IWorkbenchCommandConstants.FILE_REFRESH));
                }
            }
            manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));

            if (menuListener != null) {
                menuListener.menuAboutToShow(manager);
            }
        });

        menuMgr.setRemoveAllWhenShown(true);
        control.setMenu(menu);
        control.addDisposeListener(e -> menuMgr.dispose());
        return menuMgr;
    }

    public static void executeNodeAction(DBXTreeNodeHandler.Action action, Object node, IServiceLocator serviceLocator) {
        String defCommandId = null;
        if (action == DBXTreeNodeHandler.Action.open) {
            defCommandId = NavigatorCommands.CMD_OBJECT_OPEN;
        }
        String actionCommand = getNodeActionCommand(action, node, defCommandId);
        if (actionCommand != null) {
            ActionUtils.runCommand(actionCommand, new StructuredSelection(node), serviceLocator);
        } else {
            // do nothing
            // TODO: implement some other behavior
        }

    }

    public static String getNodeActionCommand(DBXTreeNodeHandler.Action action, Object node, String defCommand) {
        if (node instanceof DBNDatabaseNode) {
            DBXTreeNodeHandler handler = ((DBNDatabaseNode) node).getMeta().getHandler(action);
            if (handler != null && handler.getPerform() == DBXTreeNodeHandler.Perform.command && !CommonUtils.isEmpty(handler.getCommand())) {
                return handler.getCommand();
            }
        }
        return defCommand;
    }

    public static void addDragAndDropSupport(final Viewer viewer)
    {
        Transfer[] types = new Transfer[] {TextTransfer.getInstance(), TreeNodeTransfer.getInstance(), DatabaseObjectTransfer.getInstance()};
        int operations = DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_LINK;

        final DragSource source = new DragSource(viewer.getControl(), operations);
        source.setTransfer(types);
        source.addDragListener (new DragSourceListener() {

            private IStructuredSelection selection;

            @Override
            public void dragStart(DragSourceEvent event) {
                selection = (IStructuredSelection) viewer.getSelection();
            }

            @Override
            public void dragSetData (DragSourceEvent event) {
                if (!selection.isEmpty()) {
                    List<DBNNode> nodes = new ArrayList<>();
                    List<DBPNamedObject> objects = new ArrayList<>();
                    String lineSeparator = CommonUtils.getLineSeparator();
                    StringBuilder buf = new StringBuilder();
                    for (Iterator<?> i = selection.iterator(); i.hasNext(); ) {
                        Object nextSelected = i.next();
                        if (!(nextSelected instanceof DBNNode)) {
                            continue;
                        }
                        nodes.add((DBNNode)nextSelected);
                        String nodeName;
                        if (nextSelected instanceof DBNDatabaseNode && !(nextSelected instanceof DBNDataSource)) {
                            DBSObject object = ((DBNDatabaseNode)nextSelected).getObject();
                            if (object == null) {
                                continue;
                            }
                            nodeName = DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
                            objects.add(object);
                        } else {
                            nodeName = ((DBNNode)nextSelected).getNodeTargetName();
                        }
                        if (buf.length() > 0) {
                            buf.append(lineSeparator);
                        }
                        buf.append(nodeName);
                    }
                    if (TreeNodeTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = nodes;
                    } else if (DatabaseObjectTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = objects;
                    } else if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = buf.toString();
                    }
                } else {
                    if (TreeNodeTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = Collections.emptyList();
                    } else if (DatabaseObjectTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = Collections.emptyList();
                    } else if (TextTransfer.getInstance().isSupportedType(event.dataType)) {
                        event.data = "";
                    }
                }
            }
            @Override
            public void dragFinished(DragSourceEvent event) {
            }
        });

        DropTarget dropTarget = new DropTarget(viewer.getControl(), DND.DROP_MOVE);
        dropTarget.setTransfer(TreeNodeTransfer.getInstance());
        dropTarget.addDropListener(new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetEvent event)
            {
                handleDragEvent(event);
            }

            @Override
            public void dragLeave(DropTargetEvent event)
            {
                handleDragEvent(event);
            }

            @Override
            public void dragOperationChanged(DropTargetEvent event)
            {
                handleDragEvent(event);
            }

            @Override
            public void dragOver(DropTargetEvent event)
            {
                handleDragEvent(event);
            }

            @Override
            public void drop(DropTargetEvent event)
            {
                handleDragEvent(event);
                if (event.detail == DND.DROP_MOVE) {
                    moveNodes(event);
                }
            }

            @Override
            public void dropAccept(DropTargetEvent event)
            {
                handleDragEvent(event);
            }

            private void handleDragEvent(DropTargetEvent event)
            {
                event.detail = isDropSupported(event) ? DND.DROP_MOVE : DND.DROP_NONE;
                event.feedback = DND.FEEDBACK_SELECT;
            }

            private boolean isDropSupported(DropTargetEvent event)
            {
                if (TreeNodeTransfer.getInstance().isSupportedType(event.currentDataType)) {
                    Object curObject;
                    if (event.item instanceof Item) {
                        curObject = event.item.getData();
                    } else {
                        curObject = null;
                    }
                    @SuppressWarnings("unchecked")
                    Collection<DBNNode> nodesToDrop = (Collection<DBNNode>) event.data;
                    if (curObject instanceof DBNNode) {
                        if (!CommonUtils.isEmpty(nodesToDrop)) {
                            for (DBNNode node : nodesToDrop) {
                                if (!((DBNNode)curObject).supportsDrop(node)) {
                                    return false;
                                }
                            }
                            return true;
                        } else {
                            return ((DBNNode)curObject).supportsDrop(null);
                        }
                    } else if (curObject == null) {
                        // Drop to empty area
                        if (!CommonUtils.isEmpty(nodesToDrop)) {
                            for (DBNNode node : nodesToDrop) {
                                if (!(node instanceof DBNDataSource)) {
                                    return false;
                                }
                            }
                            return true;
                        } else {
                            Widget widget = event.widget;
                            if (widget instanceof DropTarget) {
                                widget = ((DropTarget) widget).getControl();
                            }
                            return widget == viewer.getControl();
                        }
                    }
                }
                return false;
            }

            private void moveNodes(DropTargetEvent event)
            {
                if (TreeNodeTransfer.getInstance().isSupportedType(event.currentDataType)) {
                    Object curObject;
                    if (event.item instanceof Item) {
                        curObject = event.item.getData();
                    } else {
                        curObject = null;
                    }
                    if (curObject instanceof DBNNode) {
                        Collection<DBNNode> nodesToDrop = TreeNodeTransfer.getInstance().getObject();
                        try {
                            ((DBNNode)curObject).dropNodes(nodesToDrop);
                        } catch (DBException e) {
                            DBWorkbench.getPlatformUI().showError("Drop error", "Can't drop node", e);
                        }
                    } else if (curObject == null) {
                        for (DBNNode node : TreeNodeTransfer.getInstance().getObject()) {
                            if (node instanceof DBNDataSource) {
                                ((DBNDataSource) node).setFolder(null);
                            } else if (node instanceof DBNLocalFolder) {
                                ((DBNLocalFolder) node).getFolder().setParent(null);
                            } else {
                                continue;
                            }
                            DBNModel.updateConfigAndRefreshDatabases(node);
                        }
                    }
                }
            }
        });
    }

    public static NavigatorViewBase getActiveNavigatorView(ExecutionEvent event) {
        IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
        if (activePart instanceof NavigatorViewBase) {
            return (NavigatorViewBase) activePart;
        }
        final IWorkbenchPage activePage = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        activePart = activePage.findView(DatabaseNavigatorView.VIEW_ID);
        if (activePart instanceof NavigatorViewBase && activePage.isPartVisible(activePart)) {
            return (NavigatorViewBase) activePart;
        }
        activePart = activePage.findView(ProjectNavigatorView.VIEW_ID);
        if (activePart instanceof NavigatorViewBase && activePage.isPartVisible(activePart)) {
            return (NavigatorViewBase) activePart;
        }
        return null;
    }

    public static void filterSelection(final ISelection selection, boolean exclude)
    {
        if (selection instanceof IStructuredSelection) {
            Map<DBNDatabaseFolder, DBSObjectFilter> folders = new HashMap<>();
            for (Object item : ((IStructuredSelection)selection).toArray()) {
                if (item instanceof DBNNode) {
                    final DBNNode node = (DBNNode) item;
                    DBNDatabaseFolder folder = (DBNDatabaseFolder) node.getParentNode();
                    DBSObjectFilter nodeFilter = folders.get(folder);
                    if (nodeFilter == null) {
                        nodeFilter = folder.getNodeFilter(folder.getItemsMeta(), true);
                        if (nodeFilter == null) {
                            nodeFilter = new DBSObjectFilter();
                        }
                        folders.put(folder, nodeFilter);
                    }
                    if (exclude) {
                        nodeFilter.addExclude(node.getNodeName());
                    } else {
                        nodeFilter.addInclude(node.getNodeName());
                    }
                    nodeFilter.setEnabled(true);
                }
            }
            // Save folders
            for (Map.Entry<DBNDatabaseFolder, DBSObjectFilter> entry : folders.entrySet()) {
                entry.getKey().setNodeFilter(entry.getKey().getItemsMeta(), entry.getValue());
            }
            // Refresh all folders
            NavigatorHandlerRefresh.refreshNavigator(folders.keySet());
        }
    }

    public static boolean syncEditorWithNavigator(INavigatorModelView navigatorView, IEditorPart activeEditor) {
        if (!(activeEditor instanceof IDataSourceContainerProviderEx)) {
            return false;
        }
        IDataSourceContainerProviderEx dsProvider = (IDataSourceContainerProviderEx) activeEditor;
        Viewer navigatorViewer = navigatorView.getNavigatorViewer();
        if (navigatorViewer == null) {
            return false;
        }
        DBNNode selectedNode = getSelectedNode(navigatorViewer.getSelection());
        if (!(selectedNode instanceof DBNDatabaseNode)) {
            return false;
        }
        final DBPDataSourceContainer ds = ((DBNDatabaseNode) selectedNode).getDataSourceContainer();
        if (ds == null) {
            return false;
        }
        if (dsProvider.getDataSourceContainer() != ds) {
            dsProvider.setDataSourceContainer(ds);
        }
        // Now check if we can change default object
        DBSObject dbObject = ((DBNDatabaseNode) selectedNode).getObject();
        if (dbObject != null && dbObject.getParentObject() != null) {
            DBPObject parentObject = DBUtils.getPublicObject(dbObject.getParentObject());
            if (parentObject instanceof DBSObjectSelector) {
                DBSObjectSelector selector = (DBSObjectSelector) parentObject;
                DBSObject curDefaultObject = selector.getDefaultObject();
                if (curDefaultObject != dbObject) {
                    if (curDefaultObject != null && curDefaultObject.getClass() != dbObject.getClass()) {
                        // Wrong object type
                        return true;
                    }
                    try {
                        selector.setDefaultObject(new VoidProgressMonitor(), dbObject);
                    } catch (Throwable e) {
                        log.debug(e);
                    }
                }
            }
        }
        return true;
    }

    public static void openNavigatorNode(Object node, IWorkbenchWindow window) {
        if (node instanceof DBNResource) {
            UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
            if (serviceSQL != null) {
                serviceSQL.openResource(((DBNResource) node).getResource());
            }
        } else if (node instanceof DBNNode && ((DBNNode) node).allowsOpen()) {
            NavigatorHandlerObjectOpen.openEntityEditor(
                (DBNNode) node,
                null,
                window);
        }
    }

    @Nullable
    public static IStructuredSelection getSelectionFromPart(IWorkbenchPart part)
    {
        if (part == null) {
            return null;
        }
        ISelectionProvider selectionProvider = part.getSite().getSelectionProvider();
        if (selectionProvider == null) {
            return null;
        }
        ISelection selection = selectionProvider.getSelection();
        if (selection.isEmpty() || !(selection instanceof IStructuredSelection)) {
            return null;
        }
        return (IStructuredSelection)selection;
    }

    public static DBPProject getSelectedProject(ISelection currentSelection, IWorkbenchPart activePart) {
        DBPProject activeProject = null;
        if (currentSelection instanceof IStructuredSelection && !currentSelection.isEmpty()) {
            Object selItem = ((IStructuredSelection) currentSelection).getFirstElement();
            if (selItem instanceof DBNNode) {
                activeProject = ((DBNNode) selItem).getOwnerProject();
            }
        }
        if (activeProject == null) {
            if (activePart instanceof DBPContextProvider) {
                DBCExecutionContext executionContext = ((DBPContextProvider) activePart).getExecutionContext();
                if (executionContext != null) {
                    activeProject = executionContext.getDataSource().getContainer().getRegistry().getProject();
                }
            }
        }
        if (activeProject == null) {
            activeProject = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        }
        return activeProject;
    }


}
