package auto

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"

	"github.com/pkg/errors"
	"github.com/pulumi/pulumi/sdk/v2/go/common/tokens"
	"github.com/pulumi/pulumi/sdk/v2/go/common/workspace"
	"github.com/pulumi/pulumi/sdk/v2/go/pulumi"
)

type LocalWorkspace struct {
	workDir    string
	pulumiHome *string
	program    pulumi.RunFunc
}

var settingsExtensions = []string{".yaml", ".yml", ".json"}

func (l *LocalWorkspace) ProjectSettings(ctx context.Context) (*workspace.Project, error) {
	for _, ext := range settingsExtensions {
		projectPath := filepath.Join(l.WorkDir(), fmt.Sprintf("Pulumi%s", ext))
		if _, err := os.Stat(projectPath); err == nil {
			proj, err := workspace.LoadProject(projectPath)
			if err != nil {
				return nil, errors.Wrap(err, "found project settings, but failed to load")
			}
			return proj, nil
		}
	}
	return nil, errors.New("unable to find project settings in workspace")
}

func (l *LocalWorkspace) WriteProjectSettings(ctx context.Context, settings *workspace.Project) error {
	pulumiYamlPath := filepath.Join(l.WorkDir(), "Pulumi.yaml")
	return settings.Save(pulumiYamlPath)
}

func (l *LocalWorkspace) StackSettings(ctx context.Context, fqsn string) (*workspace.ProjectStack, error) {
	name, err := GetStackFromFQSN(fqsn)
	if err != nil {
		return nil, errors.Wrap(err, "failed to load stack settings, invalid stack name")
	}
	for _, ext := range settingsExtensions {
		stackPath := filepath.Join(l.WorkDir(), fmt.Sprintf("pulumi.%s%s", name, ext))
		if _, err := os.Stat(stackPath); err != nil {
			proj, err := workspace.LoadProjectStack(stackPath)
			if err != nil {
				return nil, errors.Wrap(err, "found stack settings, but failed to load")
			}
			return proj, nil
		}
	}
	return nil, errors.Errorf("unable to find stack settings in workspace for %s", fqsn)
}

func (l *LocalWorkspace) WriteStackSettings(
	ctx context.Context,
	fqsn string,
	settings *workspace.ProjectStack,
) error {
	name, err := GetStackFromFQSN(fqsn)
	if err != nil {
		return errors.Wrap(err, "failed to save stack settings, invalid stack name")
	}
	stackYamlPath := filepath.Join(l.WorkDir(), fmt.Sprintf("pulumi.%s.yaml:", name))
	err = settings.Save(stackYamlPath)
	if err != nil {
		return errors.Wrapf(err, "failed to save stack setttings for %s", fqsn)
	}
	return nil
}

func (l *LocalWorkspace) SerializeArgsForOp(ctx context.Context, fqsn string) ([]string, error) {
	// not utilized for LocalWorkspace
	return nil, nil
}

func (l *LocalWorkspace) PostOpCallback(ctx context.Context, fqsn string) error {
	// not utilized for LocalWorkspace
	return nil
}

func (l *LocalWorkspace) GetConfig(ctx context.Context, fqsn string, key string) (ConfigValue, error) {
	var val ConfigValue
	err := l.SelectStack(ctx, fqsn)
	if err != nil {
		return val, errors.Wrapf(err, "could not get config, unable to select stack %s", fqsn)
	}
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "config", "get", key, "--show-secrets", "--json")
	if err != nil {
		return val, errors.Wrap(newAutoError(err, stdout, stderr, errCode), "unable read config")
	}
	err = json.Unmarshal([]byte(stdout), &val)
	if err != nil {
		return val, errors.Wrap(err, "unable to unmarshal config value")
	}
	return val, nil
}

func (l *LocalWorkspace) GetAllConfig(ctx context.Context, fqsn string) (ConfigMap, error) {
	var val ConfigMap
	err := l.SelectStack(ctx, fqsn)
	if err != nil {
		return val, errors.Wrapf(err, "could not get config, unable to select stack %s", fqsn)
	}
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "config", "--show-secrets", "--json")
	if err != nil {
		return val, errors.Wrap(newAutoError(err, stdout, stderr, errCode), "unable read config")
	}
	err = json.Unmarshal([]byte(stdout), &val)
	if err != nil {
		return val, errors.Wrap(err, "unable to unmarshal config value")
	}
	return val, nil
}

func (l *LocalWorkspace) SetConfig(ctx context.Context, fqsn string, key string, val ConfigValue) error {
	err := l.SelectStack(ctx, fqsn)
	if err != nil {
		return errors.Wrapf(err, "could not set config, unable to select stack %s", fqsn)
	}

	secretArg := "--plaintext"
	if val.Secret {
		secretArg = "--secret"
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "config", "set", key, val.Value, secretArg)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "unable set config")
	}
	return nil
}

func (l *LocalWorkspace) SetAllConfig(ctx context.Context, fqsn string, config ConfigMap) error {
	for k, v := range config {
		err := l.SetConfig(ctx, fqsn, k, v)
		if err != nil {
			return err
		}
	}
	return nil
}

func (l *LocalWorkspace) RemoveConfig(ctx context.Context, fqsn string, key string) error {
	err := l.SelectStack(ctx, fqsn)
	if err != nil {
		return errors.Wrapf(err, "could not remove config, unable to select stack %s", fqsn)
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "config", "rm", key)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "could not remove config")
	}
	return nil
}

func (l *LocalWorkspace) RemoveAllConfig(ctx context.Context, fqsn string, keys []string) error {
	for _, k := range keys {
		err := l.RemoveConfig(ctx, fqsn, k)
		if err != nil {
			return err
		}
	}
	return nil
}

func (l *LocalWorkspace) RefreshConfig(ctx context.Context, fqsn string) (ConfigMap, error) {
	err := l.SelectStack(ctx, fqsn)
	if err != nil {
		return nil, errors.Wrapf(err, "could not refresh config, unable to select stack %s", fqsn)
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "config", "refresh", "--force")
	if err != nil {
		return nil, errors.Wrap(newAutoError(err, stdout, stderr, errCode), "could not refresh config")
	}

	cfg, err := l.GetAllConfig(ctx, fqsn)
	if err != nil {
		return nil, errors.Wrap(err, "could not fetch config after refresh")
	}
	return cfg, nil
}

func (l *LocalWorkspace) WorkDir() string {
	return l.workDir
}

func (l *LocalWorkspace) PulumiHome() *string {
	return l.pulumiHome
}

func (l *LocalWorkspace) WhoAmI(ctx context.Context) (string, error) {
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "whoami")
	if err != nil {
		return "", errors.Wrap(newAutoError(err, stdout, stderr, errCode), "could not determine authenticated user")
	}
	return strings.TrimSpace(stdout), nil
}

func (l *LocalWorkspace) Stack(ctx context.Context) (*StackSummary, error) {
	stacks, err := l.ListStacks(ctx)
	if err != nil {
		return nil, errors.Wrap(err, "could not determine selected stack")
	}
	for _, s := range stacks {
		if s.Current {
			return &s, nil
		}
	}
	return nil, nil
}

func (l *LocalWorkspace) CreateStack(ctx context.Context, fqsn string) error {
	err := ValidateFullyQualifiedStackName(fqsn)
	if err != nil {
		return errors.Wrap(err, "failed to create stack")
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "stack", "init", fqsn)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "failed to create stack")
	}

	return nil
}

func (l *LocalWorkspace) SelectStack(ctx context.Context, fqsn string) error {
	err := ValidateFullyQualifiedStackName(fqsn)
	if err != nil {
		return errors.Wrap(err, "failed to select stack")
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "stack", "select", fqsn)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "failed to select stack")
	}

	return nil
}

func (l *LocalWorkspace) RemoveStack(ctx context.Context, fqsn string) error {
	err := ValidateFullyQualifiedStackName(fqsn)
	if err != nil {
		return errors.Wrap(err, "failed to remove stack")
	}

	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "stack", "rm", "--yes", fqsn)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "failed to remove stack")
	}
	return nil
}

func (l *LocalWorkspace) ListStacks(ctx context.Context) ([]StackSummary, error) {
	user, err := l.WhoAmI(ctx)
	if err != nil {
		return nil, errors.Wrap(err, "could not list stacks")
	}

	proj, err := l.ProjectSettings(ctx)
	if err != nil {
		return nil, errors.Wrap(err, "could not list stacks")
	}

	var stacks []StackSummary
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "stack", "ls", "--json")
	if err != nil {
		return stacks, errors.Wrap(newAutoError(err, stdout, stderr, errCode), "could not list stacks")
	}
	err = json.Unmarshal([]byte(stdout), &stacks)
	if err != nil {
		return nil, errors.Wrap(err, "unable to unmarshal config value")
	}
	for _, s := range stacks {
		nameParts := strings.Split(s.Name, "/")
		if len(nameParts) == 1 {
			s.Name = fmt.Sprintf("%s/%s/%s", user, proj.Name.String(), s.Name)
		} else {
			s.Name = fmt.Sprintf("%s/%s/%s", nameParts[0], proj.Name.String(), nameParts[1])
		}
	}
	return stacks, nil
}

func (l *LocalWorkspace) InstallPlugin(ctx context.Context, name string, version string) error {
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "plugin", "install", "resource", name, version)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "failed to install plugin")
	}
	return nil
}

func (l *LocalWorkspace) RemovePlugin(ctx context.Context, name string, version string) error {
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "plugin", "rm", "resource", name, version)
	if err != nil {
		return errors.Wrap(newAutoError(err, stdout, stderr, errCode), "failed to remove plugin")
	}
	return nil
}

func (l *LocalWorkspace) ListPlugins(ctx context.Context) ([]workspace.PluginInfo, error) {
	stdout, stderr, errCode, err := l.runPulumiCmdSync(ctx, "plugin", "ls", "--json")
	if err != nil {
		return nil, errors.Wrap(newAutoError(err, stdout, stderr, errCode), "could not list list")
	}
	var plugins []workspace.PluginInfo
	err = json.Unmarshal([]byte(stdout), &plugins)
	if err != nil {
		return nil, errors.Wrap(err, "unable to unmarshal plugin response")
	}
	return plugins, nil
}

func (l *LocalWorkspace) Program() pulumi.RunFunc {
	return l.program
}

func (l *LocalWorkspace) SetProgram(fn pulumi.RunFunc) {
	l.program = fn
}

func (l *LocalWorkspace) runPulumiCmdSync(
	ctx context.Context,
	args ...string,
) (string, string, int, error) {
	var env []string
	if l.PulumiHome() != nil {
		homeEnv := fmt.Sprintf("%s=%s", PulumiHomeEnv, *l.PulumiHome())
		env = append(env, homeEnv)
	}
	return runPulumiCommandSync(ctx, l.WorkDir(), env, args...)
}

func NewLocalWorkspace(ctx context.Context, opts ...LocalWorkspaceOption) (Workspace, error) {
	lwOpts := &localWorkspaceOptions{}
	// for merging options, last specified value wins
	for _, opt := range opts {
		opt.applyLocalWorkspaceOption(lwOpts)
	}

	var workDir string

	if lwOpts.WorkDir != "" {
		workDir = lwOpts.WorkDir
	} else {
		dir, err := ioutil.TempDir("", "pulumi_auto")
		if err != nil {
			return nil, errors.Wrap(err, "unable to create tmp directory for workspace")
		}
		workDir = dir
	}

	if lwOpts.Repo != nil {
		// now do the git clone
		projDir, err := setupGitRepo(ctx, workDir, lwOpts.Repo)
		if err != nil {
			return nil, errors.Wrap(err, "failed to create workspace, unable to enlist in git repo")
		}
		workDir = projDir
	}

	var program pulumi.RunFunc
	if lwOpts.Program != nil {
		program = lwOpts.Program
	}

	var pulumiHome *string
	if lwOpts.PulumiHome != nil {
		pulumiHome = lwOpts.PulumiHome
	}

	l := &LocalWorkspace{
		workDir:    workDir,
		program:    program,
		pulumiHome: pulumiHome,
	}

	if lwOpts.Project != nil {
		err := l.WriteProjectSettings(ctx, lwOpts.Project)
		if err != nil {
			return nil, errors.Wrap(err, "failed to create workspace, unable to save project settings")
		}
	}

	for fqsn := range lwOpts.Stacks {
		s := lwOpts.Stacks[fqsn]
		err := l.WriteStackSettings(ctx, fqsn, &s)
		if err != nil {
			return nil, errors.Wrap(err, "failed to create workspace")
		}
	}

	return l, nil
}

type localWorkspaceOptions struct {
	// WorkDir is the directory to execute commands from and store state.
	// Defaults to a tmp dir.
	WorkDir string
	// Program is the Pulumi Program to execute. If none is supplied,
	// the program identified in $WORKDIR/pulumi.yaml will be used instead.
	Program pulumi.RunFunc
	// PulumiHome overrides the metadata directory for pulumi commands
	PulumiHome *string
	// Project is the project settings for the workspace
	Project *workspace.Project
	// Stacks is a map of [fqsn -> stack settings objects] to seed the workspace
	Stacks map[string]workspace.ProjectStack
	// Repo is a git repo with a Pulumi Project to clone into the WorkDir.
	Repo *GitRepo
}

type LocalWorkspaceOption interface {
	applyLocalWorkspaceOption(*localWorkspaceOptions)
}

type localWorkspaceOption func(*localWorkspaceOptions)

func (o localWorkspaceOption) applyLocalWorkspaceOption(opts *localWorkspaceOptions) {
	o(opts)
}

type GitRepo struct {
	// URL to clone git repo
	URL string
	// Optional path relative to the repo root specifying location of the pulumi program.
	// Specifying this option will update the Worspace's WorkDir accordingly.
	ProjectPath string
	// Optional branch to checkout
	Branch string
	// Optional commit to checkout
	CommitHash string
	// Optional function to execute after enlisting in the specified repo.
	Setup SetupFn
}

// SetupFn is a function to execute after enlisting in a git repo.
// It is called with a PATH containing the pulumi program post-enlistment.
type SetupFn func(context.Context, string) error

// WorkDir is the directory to execute commands from and store state.
func WorkDir(workDir string) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.WorkDir = workDir
	})
}

// Program is the Pulumi Program to execute. If none is supplied,
// the program identified in $WORKDIR/pulumi.yaml will be used instead.
func Program(program pulumi.RunFunc) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.Program = program
	})
}

// PulumiHome overrides the metadata directory for pulumi commands
func PulumiHome(dir string) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.PulumiHome = &dir
	})
}

// Project sets project settings for the workspace
func Project(settings workspace.Project) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.Project = &settings
	})
}

// Stacks is a list of stack settings objects to seed the workspace
func Stacks(settings map[string]workspace.ProjectStack) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.Stacks = settings
	})
}

// Repo is a git repo with a Pulumi Project to clone into the WorkDir.
func Repo(gitRepo GitRepo) LocalWorkspaceOption {
	return localWorkspaceOption(func(lo *localWorkspaceOptions) {
		lo.Repo = &gitRepo
	})
}

func GetStackFromFQSN(fqsn string) (string, error) {
	if err := ValidateFullyQualifiedStackName(fqsn); err != nil {
		return "", err
	}
	return strings.Split(fqsn, "/")[2], nil
}

func ValidateFullyQualifiedStackName(fqsn string) error {
	parts := strings.Split(fqsn, "/")
	if len(parts) != 3 {
		return errors.Errorf(
			"invalid fully qualified stack name: %s, expected in the form 'org/project/stack'",
			fqsn,
		)
	}
	return nil
}

func NewStackLocalSource(ctx context.Context, fqsn, workDir string, opts ...LocalWorkspaceOption) (Stack, error) {
	opts = append(opts, WorkDir(workDir))
	w, err := NewLocalWorkspace(ctx, opts...)
	var stack Stack
	if err != nil {
		return stack, errors.Wrap(err, "failed to create stack")
	}

	return NewStack(ctx, fqsn, w)
}

func SelectStackLocalSource(ctx context.Context, fqsn, workDir string, opts ...LocalWorkspaceOption) (Stack, error) {
	opts = append(opts, WorkDir(workDir))
	w, err := NewLocalWorkspace(ctx, opts...)
	var stack Stack
	if err != nil {
		return stack, errors.Wrap(err, "failed to select stack")
	}

	return SelectStack(ctx, fqsn, w)
}

func NewStackRemoteSource(ctx context.Context, fqsn string, repo GitRepo, opts ...LocalWorkspaceOption) (Stack, error) {
	opts = append(opts, Repo(repo))
	w, err := NewLocalWorkspace(ctx, opts...)
	var stack Stack
	if err != nil {
		return stack, errors.Wrap(err, "failed to create stack")
	}

	return NewStack(ctx, fqsn, w)
}

func SelectStackRemoteSource(
	ctx context.Context,
	fqsn string, repo GitRepo,
	opts ...LocalWorkspaceOption,
) (Stack, error) {
	opts = append(opts, Repo(repo))
	w, err := NewLocalWorkspace(ctx, opts...)
	var stack Stack
	if err != nil {
		return stack, errors.Wrap(err, "failed to select stack")
	}

	return SelectStack(ctx, fqsn, w)
}

func NewStackInlineSource(
	ctx context.Context,
	fqsn string,
	program pulumi.RunFunc,
	opts ...LocalWorkspaceOption,
) (Stack, error) {
	var stack Stack
	opts = append(opts, Program(program))
	proj, err := defaultInlineProject(fqsn)
	if err != nil {
		return stack, errors.Wrap(err, "failed to create stack")
	}
	// as we implictly create project on behalf of the user, prepend to opts in case the user specifies one
	opts = append([]LocalWorkspaceOption{Project(proj)}, opts...)
	w, err := NewLocalWorkspace(ctx, opts...)
	if err != nil {
		return stack, errors.Wrap(err, "failed to create stack")
	}

	return NewStack(ctx, fqsn, w)
}

func SelectStackInlineSource(
	ctx context.Context,
	fqsn string,
	program pulumi.RunFunc,
	opts ...LocalWorkspaceOption,
) (Stack, error) {
	var stack Stack
	opts = append(opts, Program(program))
	proj, err := defaultInlineProject(fqsn)
	if err != nil {
		return stack, errors.Wrap(err, "failed to select stack")
	}
	// as we implictly create project on behalf of the user, prepend to opts in case the user specifies one
	opts = append([]LocalWorkspaceOption{Project(proj)}, opts...)
	w, err := NewLocalWorkspace(ctx, opts...)
	if err != nil {
		return stack, errors.Wrap(err, "failed to select stack")
	}

	return SelectStack(ctx, fqsn, w)
}

func defaultInlineProject(fqsn string) (workspace.Project, error) {
	var proj workspace.Project
	err := ValidateFullyQualifiedStackName(fqsn)
	if err != nil {
		return proj, err
	}
	pName := strings.Split(fqsn, "/")[1]
	proj = workspace.Project{
		Name:    tokens.PackageName(pName),
		Runtime: workspace.NewProjectRuntimeInfo("go", nil),
	}

	return proj, nil
}
