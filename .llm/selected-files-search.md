# How IntelliJ Implements "Search in Selected Files"

After exploring the IntelliJ codebase, here's how the "Search in Selected Files" functionality is implemented:

## Core Mechanism

IntelliJ creates a specialized `GlobalSearchScope` that only includes the specific files that were selected. This is then used in various search operations by setting it as the custom scope in the `FindModel`.

## Key Implementation Details

1. **Scope Creation**:
   - The key class `ScopeChooserUtils` (in `platform/lang-impl/src/com/intellij/ide/util/scopeChooser/ScopeChooserUtils.java`) shows how scopes are created from selected files.
   - When searching in selected files, IntelliJ creates a `GlobalSearchScope` using the method `GlobalSearchScope.filesScope()` with the selected files.

2. **Code Example**:
   ```java
   // From ScopeChooserUtils.java
   if (PredefinedSearchScopeProviderImpl.getCurrentFileScopeName().equals(scopePresentableName)) {
     VirtualFile[] array = FileEditorManager.getInstance(project).getSelectedFiles();
     List<VirtualFile> files = ContainerUtil.createMaybeSingletonList(ArrayUtil.getFirstElement(array));
     GlobalSearchScope scope = GlobalSearchScope.filesScope(project, files, PredefinedSearchScopeProviderImpl.getCurrentFileScopeName());
     return intersectWithContentScope(project, scope);
   }
   ```

3. **Selected Files Handling**:
   - When files are selected in the IDE, they're available through `FileEditorManager.getInstance(project).getSelectedFiles()`
   - These files are then used to create a scope that only includes those specific files

4. **Multiple Files Support**:
   - For multiple selected files, the `GlobalSearchScope.filesScope()` method accepts a collection of `VirtualFile` objects
   - This creates a scope limited precisely to those files

5. **Key Classes**:
   - `GlobalSearchScope`: The base class for defining search scopes
   - `ScopeChooserUtils`: Utility class for creating different kinds of scopes
   - `FileEditorManager`: Provides access to selected files in the IDE

## Implementing in Your Plugin

To implement similar functionality in your plugin:

1. **Get Selected Files**:
   ```java
   VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
   // Or for files selected in a tool window or component:
   VirtualFile[] selectedFiles = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
   ```

2. **Create a Search Scope**:
   ```java
   // For a single file
   GlobalSearchScope singleFileScope = GlobalSearchScope.fileScope(project, selectedFile);
   
   // For multiple files
   List<VirtualFile> filesList = Arrays.asList(selectedFiles);
   GlobalSearchScope multipleFilesScope = GlobalSearchScope.filesScope(project, filesList, "Selected Files");
   ```

3. **Use the Scope in Search**:
   ```java
   FindModel findModel = new FindModel();
   findModel.setCustomScope(true);
   findModel.setCustomScope(multipleFilesScope);
   // Continue with your search operation using this findModel
   ```

In summary, the approach is straightforward - create a file-specific search scope and use it to constrain where searching occurs. This pattern can be adapted for various search-related plugin features.