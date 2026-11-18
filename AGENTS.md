## Agent skills

### Issue tracker

本项目使用 GitHub Issues。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认的五个任务分类标签。见 `docs/agents/triage-labels.md`。

### Domain docs

采用单一领域文档结构。见 `docs/agents/domain.md`。

### AgentScope Java

涉及 AgentScope 实现、调试或升级时，先读 `backend/pom.xml` 确认实际依赖，再读 `docs/adr/0004-harness-runtime-and-context.md`。查阅对应版本的[官方中文文档](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4/docs/v2/zh/docs)和[源码](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.4)；这些链接是当前 2.0.4 基线，升级时同步更新。文档与源码不一致时核对锁定构件并测试，不根据默认示例扩展工具、授权或恢复边界。目标方案使用 HarnessAgent，不表示当前依赖已完成接入。

## Design System

本项目使用 DESIGN.md 格式来定义视觉规范。
根目录下的 DESIGN.md 文件是所有 UI 决策的唯一依据。

### Mandatory instructions for UI

1. BEFORE generating any visual component, read DESIGN.md completely
2. Use EXCLUSIVELY the tokens defined in the YAML front matter
3. Follow the typographic scale without inventing intermediate sizes
4. Spacing must be multiples of the base value
5. Every interactive element needs: hover, focus, disabled
6. The "Constraints" section of DESIGN.md is inviolable

## Java 编码风格

### 格式

* 使用 **google-java-format** 或 **Checkstyle**（Google 或 Sun 风格）进行强制规范
* 每个文件只包含一个顶层的公共类型
* 保持一致的缩进：2 或 4 个空格（遵循项目标准）
* 成员顺序：常量、字段、构造函数、公共方法、受保护方法、私有方法

### 不可变性

* 对于值类型，优先使用 `record`（Java 16+）
* 默认将字段标记为 `final` —— 仅在需要时才使用可变状态
* 从公共 API 返回防御性副本：`List.copyOf()`、`Map.copyOf()`、`Set.copyOf()`
* 写时复制：返回新实例，而不是修改现有实例

```java
// GOOD — immutable value type
public record OrderSummary(Long id, String customerName, BigDecimal total) {}

// GOOD — final fields, no setters
public class Order {
    private final Long id;
    private final List<LineItem> items;

    public List<LineItem> getItems() {
        return List.copyOf(items);
    }
}
```

### 命名

遵循标准的 Java 命名约定：

* `PascalCase` 用于类、接口、记录、枚举
* `camelCase` 用于方法、字段、参数、局部变量
* `SCREAMING_SNAKE_CASE` 用于 `static final` 常量
* 包名：全小写，使用反向域名（`com.example.app.service`）

### 现代 Java 特性

在能提高代码清晰度的地方使用现代语言特性：

* **记录** 用于 DTO 和值类型（Java 16+）
* **密封类** 用于封闭的类型层次结构（Java 17+）
* 使用 `instanceof` 进行**模式匹配** —— 避免显式类型转换（Java 16+）
* **文本块** 用于多行字符串 —— SQL、JSON 模板（Java 15+）
* 使用箭头语法的**Switch 表达式**（Java 14+）
* **Switch 中的模式匹配** —— 用于处理密封类型的穷举情况（Java 21+）

```java
// Pattern matching instanceof
if (shape instanceof Circle c) {
    return Math.PI * c.radius() * c.radius();
}

// Sealed type hierarchy
public sealed interface PaymentMethod permits CreditCard, BankTransfer, Wallet {}

// Switch expression
String label = switch (status) {
    case ACTIVE -> "Active";
    case SUSPENDED -> "Suspended";
    case CLOSED -> "Closed";
};
```

### Optional 的使用

* 从可能没有结果的查找方法中返回 `Optional<T>`
* 使用 `map()`、`flatMap()`、`orElseThrow()` —— 绝不直接调用 `get()` 而不先检查 `isPresent()`
* 绝不将 `Optional` 用作字段类型或方法参数

```java
// GOOD
return repository.findById(id)
    .map(ResponseDto::from)
    .orElseThrow(() -> new OrderNotFoundException(id));

// BAD — Optional as parameter
public void process(Optional<String> name) {}
```

### 错误处理

* 对于领域错误，优先使用非受检异常
* 创建扩展自 `RuntimeException` 的领域特定异常
* 避免宽泛的 `catch (Exception e)`，除非在最顶层的处理器中
* 在异常消息中包含上下文信息

```java
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("Order not found: id=" + id);
    }
}
```

### Streams

* 使用 Streams 进行转换；保持流水线简短（最多 3-4 个操作）
* 在可读性好的情况下，优先使用方法引用：`.map(Order::getTotal)`
* 避免在 Streams 操作中产生副作用
* 对于复杂逻辑，优先使用循环而不是难以理解的 Streams 流水线

### 参考

完整编码标准及示例，请参阅技能：`java-coding-standards`。
JPA/Hibernate 实体设计模式，请参阅技能：`jpa-patterns`。


## Java 模式

### 仓储模式

将数据访问封装在接口之后：

```java
public interface OrderRepository {
    Optional<Order> findById(Long id);
    List<Order> findAll();
    Order save(Order order);
    void deleteById(Long id);
}
```

具体的实现类处理存储细节（JPA、JDBC、用于测试的内存存储等）。

### 服务层

业务逻辑放在服务类中；保持控制器和仓储层的精简：

```java
public class OrderService {
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;

    public OrderService(OrderRepository orderRepository, PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
    }

    public OrderSummary placeOrder(CreateOrderRequest request) {
        var order = Order.from(request);
        paymentGateway.charge(order.total());
        var saved = orderRepository.save(order);
        return OrderSummary.from(saved);
    }
}
```

### 构造函数注入

始终使用构造函数注入 —— 绝不使用字段注入：

```java
// GOOD — constructor injection (testable, immutable)
public class NotificationService {
    private final EmailSender emailSender;

    public NotificationService(EmailSender emailSender) {
        this.emailSender = emailSender;
    }
}

// BAD — field injection (untestable without reflection, requires framework magic)
public class NotificationService {
    @Inject // or @Autowired
    private EmailSender emailSender;
}
```

### DTO 映射

使用记录（record）作为 DTO。在服务层/控制器边界进行映射：

```java
public record OrderResponse(Long id, String customer, BigDecimal total) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerName(), order.getTotal());
    }
}
```

### 建造者模式

用于具有多个可选参数的对象：

```java
public class SearchCriteria {
    private final String query;
    private final int page;
    private final int size;
    private final String sortBy;

    private SearchCriteria(Builder builder) {
        this.query = builder.query;
        this.page = builder.page;
        this.size = builder.size;
        this.sortBy = builder.sortBy;
    }

    public static class Builder {
        private String query = "";
        private int page = 0;
        private int size = 20;
        private String sortBy = "id";

        public Builder query(String query) { this.query = query; return this; }
        public Builder page(int page) { this.page = page; return this; }
        public Builder size(int size) { this.size = size; return this; }
        public Builder sortBy(String sortBy) { this.sortBy = sortBy; return this; }
        public SearchCriteria build() { return new SearchCriteria(this); }
    }
}
```

### 使用密封类型构建领域模型

```java
public sealed interface PaymentResult permits PaymentSuccess, PaymentFailure {
    record PaymentSuccess(String transactionId, BigDecimal amount) implements PaymentResult {}
    record PaymentFailure(String errorCode, String message) implements PaymentResult {}
}

// Exhaustive handling (Java 21+)
String message = switch (result) {
    case PaymentSuccess s -> "Paid: " + s.transactionId();
    case PaymentFailure f -> "Failed: " + f.errorCode();
};
```

### API 响应封装

统一的 API 响应格式：

```java
public record ApiResponse<T>(boolean success, T data, String error) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
```

### 参考

有关 Spring Boot 架构模式，请参见技能：`springboot-patterns`。
有关使用 Camel 和 Panache 的 Quarkus 架构模式，请参见技能：`quarkus-patterns`。
有关实体设计和查询优化，请参见技能：`jpa-patterns`。

## Java 安全

### 密钥管理

* 切勿在源代码中硬编码 API 密钥、令牌或凭据
* 使用环境变量：`System.getenv("API_KEY")`
* 生产环境密钥请使用密钥管理器（如 Vault、AWS Secrets Manager）
* 包含密钥的本地配置文件应放在 `.gitignore` 中

```java
// BAD
private static final String API_KEY = "sk-abc123...";

// GOOD — environment variable
String apiKey = System.getenv("PAYMENT_API_KEY");
Objects.requireNonNull(apiKey, "PAYMENT_API_KEY must be set");
```

### SQL 注入防护

* 始终使用参数化查询——切勿将用户输入拼接到 SQL 语句中
* 使用 `PreparedStatement` 或你所使用框架的参数化查询 API
* 对用于原生查询的任何输入进行验证和清理

```java
// BAD — SQL injection via string concatenation
Statement stmt = conn.createStatement();
String sql = "SELECT * FROM orders WHERE name = '" + name + "'";
stmt.executeQuery(sql);

// GOOD — PreparedStatement with parameterized query
PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE name = ?");
ps.setString(1, name);

// GOOD — JDBC template
jdbcTemplate.query("SELECT * FROM orders WHERE name = ?", mapper, name);
```

### 输入验证

* 在处理前，于系统边界处验证所有用户输入
* 使用验证框架时，在 DTO 上使用 Bean 验证（`@NotNull`, `@NotBlank`, `@Size`）
* 在使用文件路径和用户提供的字符串前，对其进行清理
* 对于验证失败的输入，应拒绝并提供清晰的错误信息

```java
// Validate manually in plain Java
public Order createOrder(String customerName, BigDecimal amount) {
    if (customerName == null || customerName.isBlank()) {
        throw new IllegalArgumentException("Customer name is required");
    }
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Amount must be positive");
    }
    return new Order(customerName, amount);
}
```

### 认证与授权

* 切勿自行实现认证加密逻辑——请使用成熟的库
* 使用 bcrypt 或 Argon2 存储密码，切勿使用 MD5/SHA1
* 在服务边界强制执行授权检查
* 清理日志中的敏感数据——切勿记录密码、令牌或个人身份信息

### 依赖项安全

* 运行 `mvn dependency:tree` 或 `./gradlew dependencies` 来审计传递依赖项
* 使用 OWASP Dependency-Check 或 Snyk 扫描已知的 CVE
* 保持依赖项更新——设置 Dependabot 或 Renovate

### 错误信息

* 切勿在 API 响应中暴露堆栈跟踪、内部路径或 SQL 错误
* 在处理器边界将异常映射为安全、通用的客户端消息
* 在服务器端记录详细错误；向客户端返回通用消息

```java
// Log the detail, return a generic message
try {
    return orderService.findById(id);
} catch (OrderNotFoundException ex) {
    log.warn("Order not found: id={}", id);
    return ApiResponse.error("Resource not found");  // generic, no internals
} catch (Exception ex) {
    log.error("Unexpected error processing order id={}", id, ex);
    return ApiResponse.error("Internal server error");  // never expose ex.getMessage()
}
```

### 参考

关于 Spring Security 认证与授权模式，请参见技能：`springboot-security`。
关于使用 JWT/OIDC、RBAC 和 CDI 的 Quarkus 安全模式，请参见技能：`quarkus-security`。
关于通用安全检查清单，请参见技能：`security-review`。


## Java 测试

### 测试框架

* **JUnit 5** (`@Test`, `@ParameterizedTest`, `@Nested`, `@DisplayName`)
* **AssertJ** 用于流式断言 (`assertThat(result).isEqualTo(expected)`)
* **Mockito** 用于模拟依赖
* **Testcontainers** 用于需要数据库或服务的集成测试

### 测试组织

```
src/test/java/com/example/app/
  service/           # 服务层单元测试
  controller/        # Web 层/API 测试
  repository/        # 数据访问测试
  integration/       # 跨层集成测试
```

在 `src/test/java` 中镜像 `src/main/java` 的包结构。

### 单元测试模式

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository);
    }

    @Test
    @DisplayName("findById returns order when exists")
    void findById_existingOrder_returnsOrder() {
        var order = new Order(1L, "Alice", BigDecimal.TEN);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        var result = orderService.findById(1L);

        assertThat(result.customerName()).isEqualTo("Alice");
        verify(orderRepository).findById(1L);
    }

    @Test
    @DisplayName("findById throws when order not found")
    void findById_missingOrder_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(99L))
            .isInstanceOf(OrderNotFoundException.class)
            .hasMessageContaining("99");
    }
}
```

### 参数化测试

```java
@ParameterizedTest
@CsvSource({
    "100.00, 10, 90.00",
    "50.00, 0, 50.00",
    "200.00, 25, 150.00"
})
@DisplayName("discount applied correctly")
void applyDiscount(BigDecimal price, int pct, BigDecimal expected) {
    assertThat(PricingUtils.discount(price, pct)).isEqualByComparingTo(expected);
}
```

### 集成测试

使用 Testcontainers 进行真实的数据库集成：

```java
@Testcontainers
class OrderRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private OrderRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        repository = new JdbcOrderRepository(dataSource);
    }

    @Test
    void save_and_findById() {
        var saved = repository.save(new Order(null, "Bob", BigDecimal.ONE));
        var found = repository.findById(saved.getId());
        assertThat(found).isPresent();
    }
}
```

关于 Spring Boot 集成测试，请参阅技能：`springboot-tdd`。
关于 Quarkus 集成测试，请参阅技能：`quarkus-tdd`。

### 测试命名

使用带有 `@DisplayName` 的描述性名称：

* `methodName_scenario_expectedBehavior()` 用于方法名
* `@DisplayName("human-readable description")` 用于报告

### 覆盖率

* 目标为 80%+ 的行覆盖率
* 使用 JaCoCo 生成覆盖率报告
* 重点关注服务和领域逻辑 — 跳过简单的 getter/配置类

### 参考

关于使用 MockMvc 和 Testcontainers 的 Spring Boot TDD 模式，请参阅技能：`springboot-tdd`。
关于使用 REST Assured 和 Camel 测试的 Quarkus TDD 模式，请参阅技能：`quarkus-tdd`。
关于测试期望，请参阅技能：`java-coding-standards`。

## TypeScript/JavaScript 编码风格

### 类型与接口

使用类型使公共 API、共享模型和组件属性显式化、可读且可复用。

#### 公共 API

* 为导出的函数、共享工具函数和公共类方法添加参数类型和返回类型
* 让 TypeScript 推断明显的局部变量类型
* 将重复的内联对象结构提取为命名类型或接口

```typescript
// WRONG: Exported function without explicit types
export function formatUser(user) {
  return `${user.firstName} ${user.lastName}`
}

// CORRECT: Explicit types on public APIs
interface User {
  firstName: string
  lastName: string
}

export function formatUser(user: User): string {
  return `${user.firstName} ${user.lastName}`
}
```

#### 接口与类型别名

* 使用 `interface` 定义可能被扩展或实现的对象结构
* 使用 `type` 定义联合类型、交叉类型、元组、映射类型和工具类型
* 优先使用字符串字面量联合类型而非 `enum`，除非需要 `enum` 以实现互操作性

```typescript
interface User {
  id: string
  email: string
}

type UserRole = 'admin' | 'member'
type UserWithRole = User & {
  role: UserRole
}
```

#### 避免使用 `any`

* 在应用程序代码中避免使用 `any`
* 对外部或不受信任的输入使用 `unknown`，然后安全地缩小其类型范围
* 当值的类型依赖于调用者时，使用泛型

```typescript
// WRONG: any removes type safety
function getErrorMessage(error: any) {
  return error.message
}

// CORRECT: unknown forces safe narrowing
function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }

  return 'Unexpected error'
}
```

#### React 属性

* 使用命名的 `interface` 或 `type` 定义组件属性
* 显式地定义回调属性类型
* 除非有特定原因，否则不要使用 `React.FC`

```typescript
interface User {
  id: string
  email: string
}

interface UserCardProps {
  user: User
  onSelect: (id: string) => void
}

function UserCard({ user, onSelect }: UserCardProps) {
  return <button onClick={() => onSelect(user.id)}>{user.email}</button>
}
```

#### JavaScript 文件

* 在 `.js` 和 `.jsx` 文件中，当类型能提高清晰度且迁移到 TypeScript 不可行时，使用 JSDoc
* 保持 JSDoc 与运行时行为一致

```javascript
/**
 * @param {{ firstName: string, lastName: string }} user
 * @returns {string}
 */
export function formatUser(user) {
  return `${user.firstName} ${user.lastName}`
}
```

### 不可变性

使用展开运算符进行不可变更新：

```typescript
interface User {
  id: string
  name: string
}

// WRONG: Mutation
function updateUser(user: User, name: string): User {
  user.name = name // MUTATION!
  return user
}

// CORRECT: Immutability
function updateUser(user: Readonly<User>, name: string): User {
  return {
    ...user,
    name
  }
}
```

### 错误处理

使用 async/await 配合 try-catch 并安全地缩小未知错误类型范围：

```typescript
interface User {
  id: string
  email: string
}

declare function riskyOperation(userId: string): Promise<User>

function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }

  return 'Unexpected error'
}

const logger = {
  error: (message: string, error: unknown) => {
    // Replace with your production logger (for example, pino or winston).
  }
}

async function loadUser(userId: string): Promise<User> {
  try {
    const result = await riskyOperation(userId)
    return result
  } catch (error: unknown) {
    logger.error('Operation failed', error)
    throw new Error(getErrorMessage(error))
  }
}
```

### 输入验证

使用 Zod 进行基于模式的验证，并从模式推断类型：

```typescript
import { z } from 'zod'

const userSchema = z.object({
  email: z.string().email(),
  age: z.number().int().min(0).max(150)
})

type UserInput = z.infer<typeof userSchema>

const validated: UserInput = userSchema.parse(input)
```

### Console.log

* 生产代码中不允许出现 `console.log` 语句
* 请使用适当的日志库替代
* 查看钩子以进行自动检测


## TypeScript/JavaScript 模式

### API 响应格式

```typescript
interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
  meta?: {
    total: number
    page: number
    limit: number
  }
}
```

### 自定义 Hooks 模式

```typescript
export function useDebounce<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value)

  useEffect(() => {
    const handler = setTimeout(() => setDebouncedValue(value), delay)
    return () => clearTimeout(handler)
  }, [value, delay])

  return debouncedValue
}
```

### 仓库模式

```typescript
interface Repository<T> {
  findAll(filters?: Filters): Promise<T[]>
  findById(id: string): Promise<T | null>
  create(data: CreateDto): Promise<T>
  update(id: string, data: UpdateDto): Promise<T>
  delete(id: string): Promise<void>
}
```
## TypeScript/JavaScript 安全

### 密钥管理

```typescript
// NEVER: Hardcoded secrets
const apiKey = "sk-proj-xxxxx"

// ALWAYS: Environment variables
const apiKey = process.env.API_KEY

if (!apiKey) {
  throw new Error('API_KEY not configured')
}
```

## TypeScript/JavaScript 测试

## E2E 测试

使用 **Playwright** 作为关键用户流程的 E2E 测试框架。
